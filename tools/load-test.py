#!/usr/bin/env python3
"""
Multi-protocol load test for Traccar: GT06, Suntech, Osmand, Teltonika, TK103, Meiligao, Ruptela, GL200.
One report per device; shared backoff, rate limit, and IMEI/device-id file.
All selected protocols run in parallel (each IMEI → one protocol, round-robin).
"""
import asyncio
import random
import struct
import time
import argparse
import sys
import urllib.parse

# São Paulo city only (município) – inland box
LAND_BOXES = [
    (-23.88, -23.40, -46.82, -46.38),
]


def random_land_position():
    box = random.choice(LAND_BOXES)
    south, north, west, east = box
    lat = random.uniform(south, north)
    lon = random.uniform(west, east)
    return lat, lon, box


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
        flags = course & 0x03FF
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
        south, north, west, east = self._box
        self.lat = max(south, min(north, self.lat + 0.0001))
        self.lon = max(west, min(east, self.lon + 0.0001))
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
        south, north, west, east = self._box
        self.lat = max(south, min(north, self.lat + 0.0001))
        self.lon = max(west, min(east, self.lon + 0.0001))
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
        south, north, west, east = self._box
        self.lat = max(south, min(north, self.lat + 0.0001))
        self.lon = max(west, min(east, self.lon + 0.0001))

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
        south, north, west, east = self._box
        self.lat = max(south, min(north, self.lat + 0.0001))
        self.lon = max(west, min(east, self.lon + 0.0001))
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
        south, north, west, east = self._box
        self.lat = max(south, min(north, self.lat + 0.0001))
        self.lon = max(west, min(east, self.lon + 0.0001))
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
        south, north, west, east = self._box
        self.lat = max(south, min(north, self.lat + 0.0001))
        self.lon = max(west, min(east, self.lon + 0.0001))
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
        south, north, west, east = self._box
        self.lat = max(south, min(north, self.lat + 0.0001))
        self.lon = max(west, min(east, self.lon + 0.0001))
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
            # data length (2) + imei 8 + type 1 (MSG_RECORDS) + records
            payload = struct.pack(">Q", imei_long) + struct.pack("B", 1) + records
            length = len(payload)
            pkt = struct.pack(">H", length) + payload
            self.writer.write(pkt)
            await self.writer.drain()
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
        south, north, west, east = self._box
        self.lat = max(south, min(north, self.lat + 0.0001))
        self.lon = max(west, min(east, self.lon + 0.0001))
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
        description="Multi-protocol load test: send to GT06, Suntech, Osmand simultaneously (single command).")
    parser.add_argument("--protocols", "-p", type=str,
                        default="gt06,suntech,osmand,teltonika,tk103,meiligao,ruptela,gl200",
                        help="Comma-separated protocols (default: all). e.g. gt06,suntech,osmand,teltonika,tk103,meiligao,ruptela,gl200")
    parser.add_argument("--host", default="localhost", help="Server host")
    parser.add_argument("--devices", type=int, default=100,
                        help="Number of devices per protocol (one report per device)")
    parser.add_argument("--reports", type=int, default=None, help="Ignored; kept for compatibility")
    parser.add_argument("--interval", type=float, default=2.0, help="Interval cycle per worker (sec)")
    parser.add_argument("--concurrency", type=int, default=20,
                        help="Concurrent workers per protocol (each protocol runs this many)")
    parser.add_argument("--close", action="store_true", help="Close connection after each report")
    parser.add_argument("--max-rps", type=float, default=None, help="Cap reports per second (per protocol)")
    parser.add_argument("--imei-file", default="load-test-imeis.txt",
                        help="File to save/load device IDs (one per line)")
    args = parser.parse_args()

    protocol_list = [s.strip().lower() for s in args.protocols.split(",") if s.strip()]
    unknown = [p for p in protocol_list if p not in PROTOCOLS]
    if unknown:
        parser.error(f"Unknown protocol(s): {unknown}. Choose from: {list(PROTOCOLS)}")
    if not protocol_list:
        parser.error("At least one protocol required.")

    if args.concurrency > 100:
        print("Warning: --concurrency > 100 per protocol can overwhelm the server.")
    if args.close and args.concurrency > 50:
        print("Warning: --close with high concurrency opens many short-lived connections.")

    imei_file = args.imei_file
    try:
        with open(imei_file, "r") as f:
            device_ids = [line.strip() for line in f if line.strip()]
    except FileNotFoundError:
        device_ids = []
    if device_ids:
        print(f"Loaded {len(device_ids)} device IDs from {imei_file} (restart mode)")
    else:
        print(f"Generating {args.devices} device IDs and saving to {imei_file}...")
        device_ids = [
            f"{358484000000000 + (i * 100000) + random.randint(0, 99999):015d}"
            for i in range(args.devices)
        ]
        with open(imei_file, "w") as f:
            f.write("\n".join(device_ids) + "\n")
    num_devices = len(device_ids)
    # Each IMEI → one protocol (round-robin). All protocols run at same time until IMEI list is done.
    device_ids_per_protocol = {p: [] for p in protocol_list}
    for i, did in enumerate(device_ids):
        device_ids_per_protocol[protocol_list[i % len(protocol_list)]].append(did)
    print(f"IMEI list: {num_devices} devices (each → one protocol, round-robin):")
    for p in protocol_list:
        print(f"  {p}: {len(device_ids_per_protocol[p])} devices")

    print(f"Protocols: {', '.join(protocol_list)} (simultaneous)")
    print(f"Host:      {args.host}")
    print(f"Workers:   {args.concurrency} per protocol ({len(protocol_list) * args.concurrency} total)")
    print(f"Mode:      {'Connection-per-report' if args.close else 'Persistent'}")
    if args.max_rps:
        print(f"Rate cap:  {args.max_rps} rps per protocol")
    print()

    start_time = time.time()
    results = await asyncio.gather(*[
        run_one_protocol(proto_key, args.host, device_ids_per_protocol[proto_key], args)
        for proto_key in protocol_list
    ])
    total_wall = time.time() - start_time

    print("\n--- Results ---")
    total_ok = 0
    total_fail = 0
    for r in results:
        print(f"  {r['name']} ({args.host}:{r['port']}): success={r['success']} failed={r['failed']} duration={r['duration']:.2f}s rps={r['rps']:.2f}")
        total_ok += r["success"]
        total_fail += r["failed"]
    print(f"  Total: success={total_ok} failed={total_fail} wall_clock={total_wall:.2f}s")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nInterrupted.")
        sys.exit(0)
