#!/usr/bin/env python3
"""
Multi-protocol load test for Traccar: GT06, Suntech, Osmand, Teltonika, TK103, Meiligao, Ruptela, GL200.
Continuous loop: each device sends every report_interval seconds; only online_pct% of devices are active.
State file: imei,lat,lon,timestamp_sec,protocol (no header). Movement: max 500m per report, coherent positions.
"""
import asyncio
import math
import os
import random
import signal
import struct
import time
import argparse
import sys
import urllib.parse

# Multi-city: São Paulo metro + Guarulhos, Barueri, Campinas, Valinhos, Embu das Artes, Jundiaí, Arujá, Poá, Mauá,
# Suzano, Jundiapeba, Mogi das Cruzes, Francisco Morato, Bragança Paulista
# Each (south, north, west, east) – devices spawn in one random city and wander within it
LAND_BOXES = [
    (-23.88, -23.40, -46.82, -46.38),   # São Paulo (município)
    (-23.49, -23.40, -46.55, -46.38),   # Guarulhos
    (-23.54, -23.47, -47.02, -46.83),   # Barueri
    (-23.10, -22.85, -47.18, -46.92),   # Campinas
    (-23.02, -22.96, -47.04, -46.92),   # Valinhos
    (-23.66, -23.61, -46.90, -46.78),   # Embu das Artes
    (-23.22, -23.14, -46.98, -46.85),   # Jundiaí
    (-23.40, -23.35, -46.38, -46.32),   # Arujá
    (-23.54, -23.51, -46.38, -46.34),   # Poá
    (-23.68, -23.62, -46.48, -46.42),   # Mauá
    (-23.56, -23.50, -46.35, -46.28),   # Suzano
    (-23.58, -23.52, -46.25, -46.18),   # Jundiapeba
    (-23.58, -23.50, -46.25, -46.15),   # Mogi das Cruzes
    (-23.30, -23.25, -46.75, -46.68),   # Francisco Morato
    (-22.98, -22.90, -46.58, -46.50),   # Bragança Paulista
]


def random_land_position():
    box = random.choice(LAND_BOXES)
    south, north, west, east = box
    # Use gaussian-style jitter so points aren't on a uniform grid; scale to ~1% of box
    span_lat = north - south
    span_lon = east - west
    lat = random.gauss((south + north) / 2, span_lat * 0.25)
    lon = random.gauss((west + east) / 2, span_lon * 0.25)
    lat = max(south, min(north, lat))
    lon = max(west, min(east, lon))
    return lat, lon, box


def wander_in_box(lat: float, lon: float, box) -> tuple:
    """Move lat/lon by a random step in a random direction (avoids grid-like drift)."""
    south, north, west, east = box
    step = random.uniform(0.0002, 0.001)
    angle = random.uniform(0, 2 * math.pi)
    lat_delta = step * math.cos(angle)
    lon_delta = step * math.sin(angle)
    new_lat = max(south, min(north, lat + lat_delta))
    new_lon = max(west, min(east, lon + lon_delta))
    return new_lat, new_lon


# Max distance per report (meters) so positions stay coherent
MAX_MOVE_METERS = 500
# Approx meters per degree at lat (for simple clamp)
METERS_PER_DEG_LAT = 111_320
def _meters_per_deg_lon(lat_deg: float) -> float:
    return METERS_PER_DEG_LAT * math.cos(math.radians(lat_deg))


def next_position_near(lat: float, lon: float, max_meters: float = MAX_MOVE_METERS) -> tuple:
    """
    Return (next_lat, next_lon, course_deg) with next point at most max_meters from (lat, lon).
    Course: 0–360, 0=North, 90=East.
    """
    angle_rad = random.uniform(0, 2 * math.pi)
    distance_m = random.uniform(50, max_meters)
    m_per_deg_lat = METERS_PER_DEG_LAT
    m_per_deg_lon = _meters_per_deg_lon(lat)
    lat_delta = (distance_m / m_per_deg_lat) * math.cos(angle_rad)
    lon_delta = (distance_m / m_per_deg_lon) * math.sin(angle_rad)
    next_lat = max(-90, min(90, lat + lat_delta))
    next_lon = max(-180, min(180, lon + lon_delta))
    course_deg = math.degrees(math.atan2(lon_delta * m_per_deg_lon, lat_delta * m_per_deg_lat))
    if course_deg < 0:
        course_deg += 360
    return next_lat, next_lon, course_deg


# ---------------------------------------------------------------------------
# GT06 (binary, port 5023)
# ---------------------------------------------------------------------------
class GT06Protocol:
    MSG_LOGIN = 0x01
    MSG_LOCATION = 0x22

    @staticmethod
    def crc16_x25(data: bytes):
        crc = 0xFFFF
        for byte in data:
            crc ^= byte
            for _ in range(8):
                if crc & 1:
                    crc = (crc >> 1) ^ 0x8408
                else:
                    crc >>= 1
        return crc ^ 0xFFFF

    @staticmethod
    def build_packet(type_code, content, serial):
        payload = struct.pack(">BB", len(content) + 5, type_code) + content + struct.pack(">H", serial)
        crc = GT06Protocol.crc16_x25(payload)
        return b'\x78\x78' + payload + struct.pack(">H", crc) + b'\x0d\x0a'

    @staticmethod
    def build_login(imei: str, serial: int):
        imei_hex = "0" + imei
        imei_bytes = bytes.fromhex(imei_hex)
        content = imei_bytes + struct.pack(">HH", 0x0001, 0x0000)
        return GT06Protocol.build_packet(GT06Protocol.MSG_LOGIN, content, serial)

    @staticmethod
    def build_location(lat, lon, speed, course, ignition, serial):
        now = time.gmtime()
        date_time = struct.pack(">BBBBBB",
                                now.tm_year - 2000, now.tm_mon, now.tm_mday,
                                now.tm_hour, now.tm_min, now.tm_sec)
        satellites = 0x0C
        lat_val = int(abs(lat) * 60 * 30000)
        lon_val = int(abs(lon) * 60 * 30000)
        flags = int(course) & 0x03FF
        if lat > 0:
            flags |= 0x0400
        if lon < 0:
            flags |= 0x0800
        flags |= 0x1000
        flags |= 0x4000
        if ignition:
            flags |= 0x8000
        content = date_time + struct.pack(">BIIBH",
                                          satellites, lat_val, lon_val, int(speed), flags)
        return GT06Protocol.build_packet(GT06Protocol.MSG_LOCATION, content, serial)


class GT06Device:
    def __init__(self, device_id: str):
        self.imei = device_id
        self.lat, self.lon, self._box = random_land_position()
        self.speed = random.uniform(20, 100)
        self.course = random.randint(0, 359)
        self.ignition = random.choice([True, False])
        self.serial = 1
        self.logged_in = False
        self.writer = None

    def move(self):
        self.lat, self.lon = wander_in_box(self.lat, self.lon, self._box)
        self.speed = max(0, min(120, self.speed + random.uniform(-5, 5)))
        self.ignition = random.choice([True, False]) if random.random() < 0.05 else self.ignition

    async def connect(self, host, port):
        if self.writer is None:
            try:
                reader, self.writer = await asyncio.wait_for(
                    asyncio.open_connection(host, port), timeout=5.0)
                self.logged_in = False
                return True
            except Exception as e:
                print(f"Connect error: {type(e).__name__}: {e}")
                return False
        return True

    def disconnect(self):
        if self.writer:
            self.writer.close()
            self.writer = None
            self.logged_in = False

    async def send_report(self, close_after=False):
        if self.writer is None:
            return False
        try:
            if not self.logged_in:
                login = GT06Protocol.build_login(self.imei, self.serial)
                self.writer.write(login)
                await self.writer.drain()
                self.logged_in = True
                self.serial += 1
                await asyncio.sleep(0.05)
            location = GT06Protocol.build_location(
                self.lat, self.lon, self.speed, self.course, self.ignition, self.serial)
            self.writer.write(location)
            await self.writer.drain()
            self.serial += 1
            self.move()
            if close_after:
                self.disconnect()
            return True
        except Exception as e:
            if not isinstance(e, ConnectionResetError):
                print(f"Send error (IMEI {self.imei}): {type(e).__name__}: {e}")
            self.disconnect()
            return False


# ---------------------------------------------------------------------------
# Suntech (text ST910, port 5011)
# ---------------------------------------------------------------------------
def build_suntech_st910(device_id: str, lat: float, lon: float, speed: float, course: float, serial: int) -> str:
    now = time.gmtime()
    date_str = time.strftime("%Y%m%d", now)
    time_str = time.strftime("%H:%M:%S", now)
    lat_str = f"{lat:.6f}"
    lon_str = f"{lon:.6f}"
    speed_str = f"{speed:09.3f}"
    course_str = f"{course:05.2f}"
    # ST910;Location;id;500;date;time;lat;lon;speed;course;0;3.8;0;1;serial
    msg = (
        f"ST910;Location;{device_id};500;{date_str};{time_str};"
        f"{lat_str};{lon_str};{speed_str};{course_str};0;3.8;0;1;{serial}\r\n"
    )
    return msg


class SuntechDevice:
    def __init__(self, device_id: str):
        self.device_id = device_id
        self.lat, self.lon, self._box = random_land_position()
        self.speed = random.uniform(20, 100)
        self.course = random.randint(0, 359)
        self.serial = 1
        self.writer = None

    def move(self):
        self.lat, self.lon = wander_in_box(self.lat, self.lon, self._box)
        self.speed = max(0, min(120, self.speed + random.uniform(-5, 5)))

    async def connect(self, host, port):
        if self.writer is None:
            try:
                reader, self.writer = await asyncio.wait_for(
                    asyncio.open_connection(host, port), timeout=5.0)
                return True
            except Exception as e:
                _err_prefix = getattr(self, "_log_prefix", "")
                print(f"[{_err_prefix}] Connect error: {type(e).__name__}: {e}" if _err_prefix else f"Connect error: {type(e).__name__}: {e}")
                return False
        return True

    def disconnect(self):
        if self.writer:
            self.writer.close()
            self.writer = None

    async def send_report(self, close_after=False):
        if self.writer is None:
            return False
        try:
            msg = build_suntech_st910(
                self.device_id, self.lat, self.lon, self.speed, self.course, self.serial)
            self.writer.write(msg.encode("ascii"))
            await self.writer.drain()
            self.serial += 1
            self.move()
            if close_after:
                self.disconnect()
            return True
        except Exception as e:
            _err_prefix = getattr(self, "_log_prefix", "")
            msg = f"Send error (id {self.device_id}): {type(e).__name__}: {e}"
            print(f"[{_err_prefix}] {msg}" if _err_prefix else msg)
            self.disconnect()
            return False


# ---------------------------------------------------------------------------
# Osmand (HTTP GET, port 5055)
# ---------------------------------------------------------------------------
def build_osmand_request(host: str, port: int, device_id: str, lat: float, lon: float, close: bool = False) -> bytes:
    ts_ms = int(time.time() * 1000)
    params = {
        "id": device_id,
        "lat": f"{lat:.6f}",
        "lon": f"{lon:.6f}",
        "timestamp": str(ts_ms),
        "speed": "0",
        "bearing": "0",
        "altitude": "0",
    }
    qs = urllib.parse.urlencode(params)
    path = "/?" + qs
    conn = "close" if close else "keep-alive"
    req = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        f"Connection: {conn}\r\n"
        f"\r\n"
    )
    return req.encode("ascii")


class OsmandDevice:
    def __init__(self, device_id: str):
        self.device_id = device_id
        self.lat, self.lon, self._box = random_land_position()
        self.writer = None
        self.reader = None

    def move(self):
        self.lat, self.lon = wander_in_box(self.lat, self.lon, self._box)

    async def connect(self, host, port):
        if self.writer is None:
            try:
                self.reader, self.writer = await asyncio.wait_for(
                    asyncio.open_connection(host, port), timeout=5.0)
                return True
            except Exception as e:
                _err_prefix = getattr(self, "_log_prefix", "")
                print(f"[{_err_prefix}] Connect error: {type(e).__name__}: {e}" if _err_prefix else f"Connect error: {type(e).__name__}: {e}")
                return False
        return True

    def disconnect(self):
        if self.writer:
            self.writer.close()
            self.writer = None
            self.reader = None

    async def send_report(self, close_after=False):
        if self.writer is None:
            return False
        try:
            req = build_osmand_request(
                getattr(self, "_host", "localhost"),
                getattr(self, "_port", 5055),
                self.device_id, self.lat, self.lon, close_after)
            self.writer.write(req)
            await self.writer.drain()
            # Read response (required so next request can reuse connection or close cleanly)
            try:
                await asyncio.wait_for(self.reader.read(8192), timeout=5.0)
            except asyncio.TimeoutError:
                pass
            self.move()
            if close_after:
                self.disconnect()
            return True
        except Exception as e:
            _err_prefix = getattr(self, "_log_prefix", "")
            if not isinstance(e, ConnectionResetError):
                msg = f"Send error (id {self.device_id}): {type(e).__name__}: {e}"
                print(f"[{_err_prefix}] {msg}" if _err_prefix else msg)
            self.disconnect()
            return False


# ---------------------------------------------------------------------------
# TK103 / TK303 (text, port 5002)
# ---------------------------------------------------------------------------
def build_tk103_message(device_id: str, lat: float, lon: float, speed: float, course: float) -> str:
    now = time.gmtime()
    ddmmyy = time.strftime("%d%m%y", now)
    hhmmss = time.strftime("%H%M%S", now)
    # Lat as ddmm.mmmmN/S, lon as dddmm.mmmmE/W
    lat_deg = int(abs(lat))
    lat_min = (abs(lat) - lat_deg) * 60
    lat_str = f"{lat_deg:02d}{lat_min:07.4f}{'N' if lat >= 0 else 'S'}"
    lon_deg = int(abs(lon))
    lon_min = (abs(lon) - lon_deg) * 60
    lon_str = f"{lon_deg:03d}{lon_min:07.4f}{'E' if lon >= 0 else 'W'}"
    return f"({device_id},BR00,{ddmmyy},A,{lat_str},{lon_str},{speed:.3f},{hhmmss},{course:.1f},0,0)\r\n"


class Tk103Device:
    def __init__(self, device_id: str):
        self.device_id = device_id
        self.lat, self.lon, self._box = random_land_position()
        self.speed = random.uniform(20, 100)
        self.course = random.randint(0, 359)
        self.writer = None

    def move(self):
        self.lat, self.lon = wander_in_box(self.lat, self.lon, self._box)
        self.speed = max(0, min(120, self.speed + random.uniform(-5, 5)))

    async def connect(self, host, port):
        if self.writer is None:
            try:
                self.reader, self.writer = await asyncio.wait_for(
                    asyncio.open_connection(host, port), timeout=5.0)
                return True
            except Exception as e:
                _err_prefix = getattr(self, "_log_prefix", "")
                print(f"[{_err_prefix}] Connect error: {type(e).__name__}: {e}" if _err_prefix else f"Connect error: {type(e).__name__}: {e}")
                return False
        return True

    def disconnect(self):
        if self.writer:
            self.writer.close()
            self.writer = None

    async def send_report(self, close_after=False):
        if self.writer is None:
            return False
        try:
            msg = build_tk103_message(self.device_id, self.lat, self.lon, self.speed, self.course)
            self.writer.write(msg.encode("ascii"))
            await self.writer.drain()
            self.move()
            if close_after:
                self.disconnect()
            return True
        except Exception as e:
            _err_prefix = getattr(self, "_log_prefix", "")
            msg = f"Send error (id {self.device_id}): {type(e).__name__}: {e}"
            print(f"[{_err_prefix}] {msg}" if _err_prefix else msg)
            self.disconnect()
            return False


# ---------------------------------------------------------------------------
# Teltonika (binary, port 5027): IMEI then Codec 8 AVL
# ---------------------------------------------------------------------------
def build_teltonika_imei(imei: str) -> bytes:
    data = imei.encode("ascii")
    return struct.pack(">H", len(data)) + data


def build_teltonika_avl(lat: float, lon: float, speed: float) -> bytes:
    ts_ms = int(time.time() * 1000)
    # Codec 8: codec 0x08, num_records 1. Record: timestamp 8, priority 1, lon 4, lat 4, alt 2, course 2, sat 1, speed 2, event 1, totalIO 1, then 4x count (0)
    longitude = int(lon * 10000000)
    latitude = int(lat * 10000000)
    altitude = 0
    angle = 0
    satellites = 12
    speed_cm = int(speed * 100)  # 0.01 km/h
    packet = (
        struct.pack(">I", 0) + struct.pack(">I", 0) + struct.pack("B", 0x08) + struct.pack("B", 1)
        + struct.pack(">Q", ts_ms) + struct.pack("B", 1)
        + struct.pack(">i", longitude) + struct.pack(">i", latitude) + struct.pack(">h", altitude)
        + struct.pack(">h", angle) + struct.pack("B", satellites) + struct.pack(">h", speed_cm)
        + struct.pack("B", 0) + struct.pack("B", 0)  # event, total IO
        + struct.pack("B", 0) + struct.pack("B", 0) + struct.pack("B", 0) + struct.pack("B", 0)  # 4x IO count
    )
    return packet


class TeltonikaDevice:
    def __init__(self, device_id: str):
        self.imei = device_id
        self.lat, self.lon, self._box = random_land_position()
        self.speed = random.uniform(20, 100)
        self.logged_in = False
        self.writer = None
        self.reader = None

    def move(self):
        self.lat, self.lon = wander_in_box(self.lat, self.lon, self._box)
        self.speed = max(0, min(120, self.speed + random.uniform(-5, 5)))

    async def connect(self, host, port):
        if self.writer is None:
            try:
                self.reader, self.writer = await asyncio.wait_for(
                    asyncio.open_connection(host, port), timeout=5.0)
                self.logged_in = False
                return True
            except Exception as e:
                _err_prefix = getattr(self, "_log_prefix", "")
                print(f"[{_err_prefix}] Connect error: {type(e).__name__}: {e}" if _err_prefix else f"Connect error: {type(e).__name__}: {e}")
                return False
        return True

    def disconnect(self):
        if self.writer:
            self.writer.close()
            self.writer = None
            self.reader = None
            self.logged_in = False

    async def send_report(self, close_after=False):
        if self.writer is None:
            return False
        try:
            if not self.logged_in:
                imei_pkt = build_teltonika_imei(self.imei)
                self.writer.write(imei_pkt)
                await self.writer.drain()
                resp = await asyncio.wait_for(self.reader.read(1), timeout=2.0)
                if resp != b"\x01":
                    raise ConnectionError("Teltonika login rejected")
                self.logged_in = True
                await asyncio.sleep(0.05)
            avl = build_teltonika_avl(self.lat, self.lon, self.speed)
            # AVL packet: 4 zero, 4 zero, then payload length (4 bytes), then payload
            length = len(avl)
            pkt = struct.pack(">I", 0) + struct.pack(">I", 0) + struct.pack(">I", length) + avl
            self.writer.write(pkt)
            await self.writer.drain()
            # Server sends 4-byte ack (number of records received)
            await asyncio.wait_for(self.reader.read(4), timeout=2.0)
            self.move()
            if close_after:
                self.disconnect()
            return True
        except Exception as e:
            _err_prefix = getattr(self, "_log_prefix", "")
            if not isinstance(e, ConnectionResetError):
                msg = f"Send error (IMEI {self.imei}): {type(e).__name__}: {e}"
                print(f"[{_err_prefix}] {msg}" if _err_prefix else msg)
            self.disconnect()
            return False


# ---------------------------------------------------------------------------
# Meiligao (binary, port 5009): @@ + length + id (7 BCD) + type 0x9955 + text payload + CRC + \r\n
# ---------------------------------------------------------------------------
def crc16_ccitt_false(data: bytes) -> int:
    crc = 0xFFFF
    for b in data:
        crc ^= (b << 8)
        for _ in range(8):
            if crc & 0x8000:
                crc = (crc << 1) ^ 0x1021
            else:
                crc <<= 1
            crc &= 0xFFFF
    return crc


def build_meiligao_position(device_id: str, lat: float, lon: float, speed: float, course: float) -> bytes:
    # ID as 7 bytes BCD (14 digits): pad with 0xF
    id_digits = (device_id + "F" * 14)[:14]
    id_bytes = bytes()
    for i in range(0, 14, 2):
        hi = int(id_digits[i], 16)
        lo = int(id_digits[i + 1], 16) if i + 1 < 14 else 0xF
        id_bytes += bytes([(hi << 4) | lo])
    # Payload: time, validity, lat, N/S, lon, E/W, speed, course, date (ddmmyy)
    now = time.gmtime()
    hhmmss = time.strftime("%H%M%S", now)
    ddmmyy = time.strftime("%d%m%y", now)
    lat_deg = int(abs(lat))
    lat_min = (abs(lat) - lat_deg) * 60
    lon_deg = int(abs(lon))
    lon_min = (abs(lon) - lon_deg) * 60
    payload = f"{hhmmss},A,{lat_deg}{lat_min:07.4f},{'N' if lat >= 0 else 'S'},{lon_deg}{lon_min:07.4f},{'E' if lon >= 0 else 'W'},{speed:.2f},{course:.2f},{ddmmyy}\r\n"
    payload_b = payload.encode("ascii")
    data_len = 2 + len(id_bytes) + 2 + len(payload_b)  # type 2 + id + type 0x9955 2 + payload
    body = b"@@" + struct.pack(">H", data_len) + id_bytes + struct.pack(">H", 0x9955) + payload_b
    crc = crc16_ccitt_false(body)
    return body + struct.pack(">H", crc) + b"\r\n"


class MeiligaoDevice:
    def __init__(self, device_id: str):
        self.device_id = device_id
        self.lat, self.lon, self._box = random_land_position()
        self.speed = random.uniform(20, 100)
        self.course = random.randint(0, 359)
        self.writer = None

    def move(self):
        self.lat, self.lon = wander_in_box(self.lat, self.lon, self._box)
        self.speed = max(0, min(120, self.speed + random.uniform(-5, 5)))

    async def connect(self, host, port):
        if self.writer is None:
            try:
                self.reader, self.writer = await asyncio.wait_for(
                    asyncio.open_connection(host, port), timeout=5.0)
                return True
            except Exception as e:
                _err_prefix = getattr(self, "_log_prefix", "")
                print(f"[{_err_prefix}] Connect error: {type(e).__name__}: {e}" if _err_prefix else f"Connect error: {type(e).__name__}: {e}")
                return False
        return True

    def disconnect(self):
        if self.writer:
            self.writer.close()
            self.writer = None

    async def send_report(self, close_after=False):
        if self.writer is None:
            return False
        try:
            pkt = build_meiligao_position(self.device_id, self.lat, self.lon, self.speed, self.course)
            self.writer.write(pkt)
            await self.writer.drain()
            self.move()
            if close_after:
                self.disconnect()
            return True
        except Exception as e:
            _err_prefix = getattr(self, "_log_prefix", "")
            if not isinstance(e, ConnectionResetError):
                msg = f"Send error (id {self.device_id}): {type(e).__name__}: {e}"
                print(f"[{_err_prefix}] {msg}" if _err_prefix else msg)
            self.disconnect()
            return False


# ---------------------------------------------------------------------------
# Ruptela (binary, port 5046): length (2) + IMEI (8 bytes long) + type (1) + records
# ---------------------------------------------------------------------------
def build_ruptela_records(lat: float, lon: float, speed: float, course: float) -> bytes:
    ts_sec = int(time.time())
    longitude = int(lon * 10000000)
    latitude = int(lat * 10000000)
    # Record: timestamp 4 (sec), ext 1, priority 1, lon 4, lat 4, alt 2, course 2 (course*100), sat 1, speed 2, hdop 1, event 1, 4x value count 0
    record = (
        struct.pack(">I", ts_sec) + struct.pack("B", 0)
        + struct.pack("B", 1)
        + struct.pack(">i", longitude) + struct.pack(">i", latitude)
        + struct.pack(">H", 0) + struct.pack(">H", int(course * 100)) + struct.pack("B", 12)
        + struct.pack(">H", int(speed * 100)) + struct.pack("B", 10)  # speed 0.01 km/h, hdop*10
        + struct.pack("B", 0)  # event
        + struct.pack("B", 0) + struct.pack("B", 0) + struct.pack("B", 0) + struct.pack("B", 0)  # io counts
    )
    return struct.pack("B", 0) + struct.pack("B", 1) + record  # records_left, count


class RuptelaDevice:
    def __init__(self, device_id: str):
        self.device_id = device_id
        self.lat, self.lon, self._box = random_land_position()
        self.speed = random.uniform(20, 100)
        self.course = random.randint(0, 359)
        self.writer = None
        self.reader = None

    def move(self):
        self.lat, self.lon = wander_in_box(self.lat, self.lon, self._box)
        self.speed = max(0, min(120, self.speed + random.uniform(-5, 5)))
        self.course = (self.course + random.randint(-10, 10)) % 360

    async def connect(self, host, port):
        if self.writer is None:
            try:
                self.reader, self.writer = await asyncio.wait_for(
                    asyncio.open_connection(host, port), timeout=5.0)
                return True
            except Exception as e:
                _err_prefix = getattr(self, "_log_prefix", "")
                print(f"[{_err_prefix}] Connect error: {type(e).__name__}: {e}" if _err_prefix else f"Connect error: {type(e).__name__}: {e}")
                return False
        return True

    def disconnect(self):
        if self.writer:
            self.writer.close()
            self.writer = None
            self.reader = None

    async def send_report(self, close_after=False):
        if self.writer is None:
            return False
        try:
            imei_long = int(self.device_id[:15])  # 15-digit IMEI as long (fits in 8 bytes)
            records = build_ruptela_records(self.lat, self.lon, self.speed, self.course)
            # Ruptela uses LengthFieldBasedFrameDecoder(1024, 0, 2, 2, 0) -> frame length = length + 4
            payload = struct.pack(">Q", imei_long) + struct.pack("B", 1) + records
            # length field value must satisfy: 2 + len(payload) == length + 4  =>  length = len(payload) - 2
            pkt = struct.pack(">H", len(payload) - 2) + payload
            self.writer.write(pkt)
            await self.writer.drain()
            # When not closing, wait for server ack so connection stays valid. When --close, skip wait to avoid blocking 3s per report.
            if not close_after:
                try:
                    await asyncio.wait_for(self.reader.read(256), timeout=3.0)
                except asyncio.TimeoutError:
                    pass
            self.move()
            if close_after:
                self.disconnect()
            return True
        except Exception as e:
            _err_prefix = getattr(self, "_log_prefix", "")
            if not isinstance(e, ConnectionResetError):
                msg = f"Send error (id {self.device_id}): {type(e).__name__}: {e}"
                print(f"[{_err_prefix}] {msg}" if _err_prefix else msg)
            self.disconnect()
            return False


# ---------------------------------------------------------------------------
# GL200 (text Concox, port 5004): +RESP:GTFRI,... with one location block
# ---------------------------------------------------------------------------
def build_gl200_message(device_id: str, lat: float, lon: float, speed: float, course: float) -> str:
    now = time.gmtime()
    yyyymmdd = time.strftime("%Y%m%d", now)
    hhmmss = time.strftime("%H%M%S", now)
    # PATTERN_FRI: +RESP:GT..., (version?), imei, ... then (location)+ then group then date, time, count
    # One location: hdop, speed, course, altitude, lon, lat, date, time, (,mcc,mnc)?
    loc = f"1.0,{speed:.1f},{course:.0f},0,{lon:.6f},{lat:.6f},{yyyymmdd},{hhmmss},,0,0"
    # After locations: group (battery etc): 1,100,80,0,0,,
    return f"+RESP:GTFRI,,{device_id},,,,,1,{loc},1,100,80,0,0,,{yyyymmdd},{hhmmss},0000\n"


class Gl200Device:
    def __init__(self, device_id: str):
        self.device_id = device_id
        self.lat, self.lon, self._box = random_land_position()
        self.speed = random.uniform(20, 100)
        self.course = random.randint(0, 359)
        self.writer = None

    def move(self):
        self.lat, self.lon = wander_in_box(self.lat, self.lon, self._box)
        self.speed = max(0, min(120, self.speed + random.uniform(-5, 5)))

    async def connect(self, host, port):
        if self.writer is None:
            try:
                self.reader, self.writer = await asyncio.wait_for(
                    asyncio.open_connection(host, port), timeout=5.0)
                return True
            except Exception as e:
                _err_prefix = getattr(self, "_log_prefix", "")
                print(f"[{_err_prefix}] Connect error: {type(e).__name__}: {e}" if _err_prefix else f"Connect error: {type(e).__name__}: {e}")
                return False
        return True

    def disconnect(self):
        if self.writer:
            self.writer.close()
            self.writer = None

    async def send_report(self, close_after=False):
        if self.writer is None:
            return False
        try:
            msg = build_gl200_message(self.device_id, self.lat, self.lon, self.speed, self.course)
            self.writer.write(msg.encode("ascii"))
            await self.writer.drain()
            self.move()
            if close_after:
                self.disconnect()
            return True
        except Exception as e:
            _err_prefix = getattr(self, "_log_prefix", "")
            if not isinstance(e, ConnectionResetError):
                msg = f"Send error (id {self.device_id}): {type(e).__name__}: {e}"
                print(f"[{_err_prefix}] {msg}" if _err_prefix else msg)
            self.disconnect()
            return False


# ---------------------------------------------------------------------------
# Protocol registry and runner
# ---------------------------------------------------------------------------
PROTOCOLS = {
    "gt06": {
        "port": 5023,
        "device_class": GT06Device,
        "name": "GT06",
    },
    "suntech": {
        "port": 5011,
        "device_class": SuntechDevice,
        "name": "Suntech (ST910)",
    },
    "osmand": {
        "port": 5055,
        "device_class": OsmandDevice,
        "name": "Osmand (HTTP)",
    },
    "teltonika": {
        "port": 5027,
        "device_class": TeltonikaDevice,
        "name": "Teltonika",
    },
    "tk103": {
        "port": 5002,
        "device_class": Tk103Device,
        "name": "TK103/TK303",
    },
    "meiligao": {
        "port": 5009,
        "device_class": MeiligaoDevice,
        "name": "Meiligao",
    },
    "ruptela": {
        "port": 5046,
        "device_class": RuptelaDevice,
        "name": "Ruptela",
    },
    "gl200": {
        "port": 5004,
        "device_class": Gl200Device,
        "name": "GL200",
    },
}


def _log(prefix: str, msg: str) -> None:
    print(f"[{prefix}] {msg}")


# ---------------------------------------------------------------------------
# State file: imei,lat,lon,timestamp_sec,protocol (no header)
# ---------------------------------------------------------------------------
def load_state(path: str, protocol_list: list[str]) -> tuple[list[dict], bool]:
    """
    Load state from CSV (no header). Legacy: lines with no comma are IMEI-only; we migrate in memory.
    Returns (state, had_legacy) so caller can save to upgrade the file.
    """
    try:
        with open(path, "r") as f:
            lines = [line.strip() for line in f if line.strip()]
    except FileNotFoundError:
        return [], False
    out = []
    had_legacy = False
    for line in lines:
        parts = [p.strip() for p in line.split(",")]
        if len(parts) >= 5:
            try:
                out.append({
                    "imei": parts[0],
                    "lat": float(parts[1]),
                    "lon": float(parts[2]),
                    "timestamp_sec": int(parts[3]),
                    "protocol": parts[4].lower(),
                })
            except (ValueError, IndexError):
                continue
        elif len(parts) == 1 and parts[0].isdigit():
            had_legacy = True
            lat, lon, _ = random_land_position()
            proto = protocol_list[len(out) % len(protocol_list)]
            out.append({
                "imei": parts[0],
                "lat": lat,
                "lon": lon,
                "timestamp_sec": 0,
                "protocol": proto,
            })
    return out, had_legacy


def save_state(path: str, state: list[dict]) -> None:
    """
    Write full state to CSV (no header). Writes to temp then atomically renames.
    Only replaces the real file if the temp has exactly len(state) lines.
    Never overwrites the existing file if it has more lines than we're writing (prevents truncation).
    """
    expected = len(state)
    lines = [f"{d['imei']},{d['lat']:.6f},{d['lon']:.6f},{d['timestamp_sec']},{d['protocol']}" for d in state]
    if len(lines) != expected:
        return  # state changed during build; skip this save
    tmp = path + ".tmp"
    try:
        replace_ok = True
        with open(tmp, "w") as f:
            content = "\n".join(lines) + "\n"
            f.write(content)
            f.flush()
            if content.count("\n") != expected:
                replace_ok = False
        if not replace_ok:
            try:
                os.unlink(tmp)
            except Exception:
                pass
            return
        # Never overwrite a file that has more lines (safety: avoid truncating e.g. after --close or bugs)
        if os.path.exists(path):
            try:
                with open(path, "r") as f:
                    current_lines = sum(1 for _ in f)
                if current_lines > expected:
                    try:
                        os.unlink(tmp)
                    except Exception:
                        pass
                    print(f"Warning: skipped save — file has {current_lines} devices, state has {expected}; not overwriting.")
                    return
            except Exception:
                pass
        os.replace(tmp, path)
    except Exception:
        try:
            os.unlink(tmp)
        except Exception:
            pass
        raise


def generate_initial_state(num_devices: int, protocol_list: list[str]) -> list[dict]:
    """Generate state entries with random initial positions and round-robin protocol."""
    state = []
    for i in range(num_devices):
        lat, lon, _ = random_land_position()
        protocol = protocol_list[i % len(protocol_list)]
        imei = f"{358484000000000 + (i * 100000) + random.randint(0, 99999):015d}"
        state.append({
            "imei": imei,
            "lat": lat,
            "lon": lon,
            "timestamp_sec": 0,
            "protocol": protocol,
        })
    return state


def state_from_device_list(path: str, protocol_list: list[str]) -> list[dict]:
    """Build state from existing device IDs: one IMEI per line, or CSV (first column = IMEI)."""
    try:
        with open(path, "r") as f:
            raw_lines = [line.strip() for line in f if line.strip()]
    except FileNotFoundError:
        return []
    imeis = []
    for line in raw_lines:
        # CSV state format: imei,lat,lon,ts,protocol -> take first column
        if "," in line:
            imeis.append(line.split(",")[0].strip())
        else:
            imeis.append(line)
    state = []
    for i, imei in enumerate(imeis):
        lat, lon, _ = random_land_position()
        protocol = protocol_list[i % len(protocol_list)]
        state.append({
            "imei": imei,
            "lat": lat,
            "lon": lon,
            "timestamp_sec": 0,
            "protocol": protocol,
        })
    return state


async def send_one_report(device_state: dict, host: str, args, rate_limit_lock: asyncio.Lock | None, min_interval: float, last_send_time: list) -> bool:
    """
    Send one report using pre-generated coordinates (coordinator already updated state and saved file).
    Uses device_state lat/lon/course; does not update state after send.
    """
    proto_key = device_state["protocol"]
    if proto_key not in PROTOCOLS:
        return False
    proto = PROTOCOLS[proto_key]
    port = proto["port"]
    device_class = proto["device_class"]
    device_id = device_state["imei"]
    lat = device_state["lat"]
    lon = device_state["lon"]
    course_deg = device_state.get("course", 0)
    speed = random.uniform(20, 80)
    device = device_class(device_id)
    device.lat = lat
    device.lon = lon
    device.speed = speed
    device.course = course_deg
    device._box = (lat - 0.005, lat + 0.005, lon - 0.005, lon + 0.005)
    device._log_prefix = proto_key
    if proto_key == "osmand":
        device._host = host
        device._port = port
    if rate_limit_lock is not None and min_interval > 0:
        async with rate_limit_lock:
            now = time.time()
            wait = last_send_time[0] + min_interval - now
            if wait > 0:
                await asyncio.sleep(wait)
            last_send_time[0] = time.time()

    # Reuse existing connection for resends (only when not --close)
    reuse = not args.close and device_state.get("_writer") is not None
    if reuse:
        device.writer = device_state["_writer"]
        device.reader = device_state.get("_reader")
        device.logged_in = True  # GT06, Teltonika: skip duplicate login
        if device_state.get("_serial") is not None:
            device.serial = device_state["_serial"]  # GT06, Suntech, etc.: correct packet serial
    else:
        if not await device.connect(host, port):
            return False

    # First send: close after. Resends: keep open (unless --close).
    is_first = device_state["timestamp_sec"] == 0
    close_after = args.close or is_first
    try:
        ok = await device.send_report(close_after=close_after)
    except Exception:
        device_state.pop("_writer", None)
        device_state.pop("_reader", None)
        device_state.pop("_serial", None)
        raise
    if not ok:
        device_state.pop("_writer", None)
        device_state.pop("_reader", None)
        device_state.pop("_serial", None)
        return False
    # State already updated by coordinator before this round; no partial update here
    if not close_after:
        device_state["_writer"] = device.writer
        device_state["_reader"] = getattr(device, "reader", None)
        device_state["_serial"] = getattr(device, "serial", None)
    return True


async def run_continuous_loop(state: list[dict], online_indices: list[int], state_file: str, args, shutdown_event: asyncio.Event):
    """
    Round-based: load (in memory) → generate all coordinates for due devices → save file once → then send.
    No partial file updates during send; file is only written once per round after generating coords.
    On Ctrl+C (shutdown_event set): stop loop and save state once in finally so file is not corrupted.
    """
    report_interval = args.report_interval
    due_list = []
    due_lock = asyncio.Lock()
    rate_limit_lock = asyncio.Lock() if args.max_rps else None
    min_interval = 1.0 / args.max_rps if args.max_rps else 0.0
    last_send_time = [0.0]
    stats = {"success": 0, "failed": 0}

    async def coordinator():
        while not shutdown_event.is_set():
            try:
                await asyncio.wait_for(asyncio.shield(shutdown_event.wait()), timeout=1.0)
            except asyncio.TimeoutError:
                pass
            if shutdown_event.is_set():
                break
            now = time.time()
            due_set = set(due_list)
            # Phase 1: first report for EVERY device in state (bootstrap), regardless of online_pct.
            # Phase 2: periodic updates only for online_indices respecting report_interval.
            bootstrap_pending = any(d["timestamp_sec"] == 0 for d in state)
            if bootstrap_pending:
                # First, send once for all devices that never reported yet (full state, not just online_indices)
                due_indices = [i for i, d in enumerate(state) if d["timestamp_sec"] == 0 and i not in due_set]
            else:
                # Normal phase: respect report_interval between reports for online devices only
                due_indices = [i for i in online_indices
                               if now - state[i]["timestamp_sec"] >= report_interval and i not in due_set]
            if not due_indices:
                continue
            # Generate all coordinates for this round, then save file once, then enqueue
            for i in due_indices:
                lat, lon = state[i]["lat"], state[i]["lon"]
                next_lat, next_lon, course_deg = next_position_near(lat, lon, MAX_MOVE_METERS)
                state[i]["lat"] = next_lat
                state[i]["lon"] = next_lon
                state[i]["timestamp_sec"] = int(now)
                state[i]["course"] = course_deg
            try:
                save_state(state_file, state)
            except Exception as e:
                print(f"Persist failed: {e}")
                continue
            async with due_lock:
                for i in due_indices:
                    due_list.append(i)

    async def worker():
        while not shutdown_event.is_set():
            idx = None
            async with due_lock:
                if due_list:
                    idx = due_list.pop(0)
            if idx is None:
                await asyncio.sleep(0.1)
                continue
            device_state = state[idx]
            ok = await send_one_report(device_state, args.host, args, rate_limit_lock, min_interval, last_send_time)
            if ok:
                stats["success"] += 1
                if stats["success"] % 1000 == 0:
                    print(f"Sent {stats['success']} reports (failed={stats['failed']})...")
            else:
                stats["failed"] += 1

    num_workers = args.concurrency
    workers = [asyncio.create_task(worker()) for _ in range(num_workers)]
    coord = asyncio.create_task(coordinator())

    async def shutdown_waiter():
        await shutdown_event.wait()
        coord.cancel()
        for w in workers:
            w.cancel()

    shutdown_task = asyncio.create_task(shutdown_waiter())
    try:
        await asyncio.gather(coord, *workers, shutdown_task)
    except asyncio.CancelledError:
        pass
    finally:
        # One final save on exit (Ctrl+C or cancel) so file is never left corrupted
        try:
            save_state(state_file, state)
            print("\nState saved.")
        except Exception as e:
            print(f"\nSave on exit failed: {e}")
        tmp_path = state_file + ".tmp"
        if os.path.exists(tmp_path):
            try:
                os.unlink(tmp_path)
            except Exception:
                pass


async def run_one_protocol(proto_key: str, host: str, device_ids: list[str], args) -> dict:
    """Run load test for one protocol; returns stats dict. All protocols run in parallel."""
    proto = PROTOCOLS[proto_key]
    port = proto["port"]
    device_class = proto["device_class"]
    prefix = proto_key

    devices = [device_class(did) for did in device_ids]
    for d in devices:
        d._log_prefix = prefix
    if proto_key == "osmand":
        for d in devices:
            d._host = host
            d._port = port

    num_devices = len(devices)
    semaphore = asyncio.Semaphore(args.concurrency)
    report_count = 0
    fail_count = 0
    device_index = 0
    device_index_lock = asyncio.Lock()
    start_time = time.time()

    backoff_lock = asyncio.Lock()
    cooldown_until = [0.0]
    FAIL_PAUSE_SECONDS = 15
    consecutive_fails = [0]
    PAUSE_AFTER_CONSECUTIVE_FAILS = 10
    LONG_PAUSE_SECONDS = 300

    rate_limit_lock = asyncio.Lock() if args.max_rps else None
    min_interval = 1.0 / args.max_rps if args.max_rps else 0.0
    last_send_time = [0.0]
    total_reports = num_devices

    async def worker(worker_id):
        nonlocal report_count, fail_count, device_index
        while device_index < num_devices:
            async with device_index_lock:
                idx = device_index
                device_index += 1
            device = devices[idx]

            async with semaphore:
                now = time.time()
                if now < cooldown_until[0]:
                    wait = cooldown_until[0] - now
                    await asyncio.sleep(wait)

                if rate_limit_lock is not None and min_interval > 0:
                    async with rate_limit_lock:
                        now = time.time()
                        wait = last_send_time[0] + min_interval - now
                        if wait > 0:
                            await asyncio.sleep(wait)
                        last_send_time[0] = time.time()

                if await device.connect(host, port):
                    if await device.send_report(close_after=args.close):
                        report_count += 1
                        consecutive_fails[0] = 0
                        if report_count % 1000 == 0:
                            elapsed = time.time() - start_time
                            _log(prefix, f"Sent {report_count}/{total_reports} reports... ({report_count/elapsed:.2f} rps)")
                        await asyncio.sleep(0.5)  # 500ms pause after each success
                    else:
                        fail_count += 1
                        async with backoff_lock:
                            consecutive_fails[0] += 1
                            if consecutive_fails[0] >= PAUSE_AFTER_CONSECUTIVE_FAILS:
                                _log(prefix, f"{PAUSE_AFTER_CONSECUTIVE_FAILS} consecutive failures: pausing {LONG_PAUSE_SECONDS // 60} min...")
                                cooldown_until[0] = time.time() + LONG_PAUSE_SECONDS
                                consecutive_fails[0] = 0
                            else:
                                _log(prefix, f"Failure: pausing {FAIL_PAUSE_SECONDS}s (fail #{fail_count}, consecutive {consecutive_fails[0]})")
                                cooldown_until[0] = time.time() + FAIL_PAUSE_SECONDS
                        await asyncio.sleep(max(0, cooldown_until[0] - time.time()))
                else:
                    fail_count += 1
                    async with backoff_lock:
                        consecutive_fails[0] += 1
                        if consecutive_fails[0] >= PAUSE_AFTER_CONSECUTIVE_FAILS:
                            _log(prefix, f"{PAUSE_AFTER_CONSECUTIVE_FAILS} consecutive failures: pausing {LONG_PAUSE_SECONDS // 60} min...")
                            cooldown_until[0] = time.time() + LONG_PAUSE_SECONDS
                            consecutive_fails[0] = 0
                        else:
                            _log(prefix, f"Failure: pausing {FAIL_PAUSE_SECONDS}s (fail #{fail_count}, consecutive {consecutive_fails[0]})")
                            cooldown_until[0] = time.time() + FAIL_PAUSE_SECONDS
                    await asyncio.sleep(max(0, cooldown_until[0] - time.time()))

                if (report_count + fail_count) % 1000 == 0 and fail_count > 0:
                    _log(prefix, f"Warning: {fail_count} failed attempts so far.")

            await asyncio.sleep(args.interval / args.concurrency)

    workers = [asyncio.create_task(worker(i)) for i in range(args.concurrency)]
    await asyncio.gather(*workers)

    total_time = time.time() - start_time
    return {
        "protocol": proto_key,
        "name": proto["name"],
        "port": port,
        "success": report_count,
        "failed": fail_count,
        "duration": total_time,
        "rps": report_count / total_time if total_time > 0 else 0,
    }


async def main():
    parser = argparse.ArgumentParser(
        description="Multi-protocol load test: continuous loop, each device reports every report_interval sec.")
    parser.add_argument("--protocols", "-p", type=str,
                        default="gt06,suntech,osmand,teltonika,tk103,meiligao,ruptela,gl200",
                        help="Comma-separated protocols (default: all)")
    parser.add_argument("--host", default="localhost", help="Server host")
    parser.add_argument("--devices", type=int, default=100,
                        help="Number of devices to generate when state file is empty")
    parser.add_argument("--report-interval", type=float, default=60,
                        help="Seconds between reports per device (default 60)")
    parser.add_argument("--online-pct", type=float, default=100,
                        help="Percentage of devices that send reports (0-100, default 100)")
    parser.add_argument("--concurrency", type=int, default=20, help="Concurrent workers")
    parser.add_argument("--close", action="store_true", help="Close connection after each report")
    parser.add_argument("--max-rps", type=float, default=None, help="Cap reports per second (global)")
    parser.add_argument("--max-open-files", type=int, default=65535,
                        help="Cap concurrent open connections (default 65535). Use e.g. 800 for OpenVZ with 1024 limit to avoid Errno 24.")
    parser.add_argument("--imei-file", default="load-test-imeis.txt",
                        help="State file: imei,lat,lon,timestamp_sec,protocol (no header)")
    parser.add_argument("--device-list", default=None,
                        help="File with existing device IDs (one IMEI per line). Use these devices instead of generating. Combined with empty state file or --regenerate.")
    parser.add_argument("--regenerate", action="store_true",
                        help="Overwrite state file (with --devices new devices, or with --device-list if given)")
    args = parser.parse_args()

    protocol_list = [s.strip().lower() for s in args.protocols.split(",") if s.strip()]
    unknown = [p for p in protocol_list if p not in PROTOCOLS]
    if unknown:
        parser.error(f"Unknown protocol(s): {unknown}. Choose from: {list(PROTOCOLS)}")
    if not protocol_list:
        parser.error("At least one protocol required.")
    if not (0 < args.online_pct <= 100):
        parser.error("--online-pct must be in (0, 100]")

    state_file = args.imei_file
    state, had_legacy = load_state(state_file, protocol_list)

    # Rule: use existing file if it has content. Overwrite only if file missing/empty or user passed --regenerate.
    if state and not args.regenerate:
        # File exists and has devices: use it. Never overwrite.
        print(f"Resuming: {len(state)} devices from {state_file}")
        if len(state) < args.devices:
            print(f"Warning: file has {len(state)} devices but --devices is {args.devices}. To regenerate the list use: --regenerate --devices {args.devices}")
        if had_legacy:
            save_state(state_file, state)
            print("Upgraded file to new format (imei,lat,lon,timestamp_sec,protocol)")
    else:
        # File missing/empty or --regenerate: create state and write file (only time we create/overwrite)
        if args.device_list:
            state = state_from_device_list(args.device_list, protocol_list)
            if not state:
                parser.error(f"--device-list file empty or not found: {args.device_list}")
            print(f"Using {len(state)} devices from {args.device_list}, saving to {state_file}...")
        else:
            state = generate_initial_state(args.devices, protocol_list)
            print(f"Generating {len(state)} devices, saving to {state_file}...")
        save_state(state_file, state)

    state = [d for d in state if d["protocol"] in protocol_list]
    if not state:
        parser.error("No devices left after filtering by --protocols")
    n_online = max(1, int(len(state) * args.online_pct / 100))
    online_indices = random.sample(range(len(state)), n_online)
    print(f"Online: {n_online} devices ({args.online_pct}%)")
    print(f"Report interval: {args.report_interval}s per device")
    print(f"Movement: max {MAX_MOVE_METERS}m per report")
    print(f"Host: {args.host}  Workers: {args.concurrency}  Close: {args.close}")
    if args.max_rps:
        print(f"Rate cap: {args.max_rps} rps")
    print(f"Max open connections: {args.max_open_files}")
    print("Running continuous loop (Ctrl+C to stop)...\n")

    shutdown_event = asyncio.Event()

    def on_shutdown(sig, frame):
        shutdown_event.set()

    signal.signal(signal.SIGINT, on_shutdown)
    try:
        signal.signal(signal.SIGTERM, on_shutdown)
    except (ValueError, OSError):
        pass  # SIGTERM not available on all platforms (e.g. Windows)
    try:
        await run_continuous_loop(state, online_indices, state_file, args, shutdown_event)
    finally:
        signal.signal(signal.SIGINT, signal.SIG_DFL)
        try:
            signal.signal(signal.SIGTERM, signal.SIG_DFL)
        except (ValueError, OSError):
            pass


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nInterrupted.")
        sys.exit(0)
