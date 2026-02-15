#!/usr/bin/env python3

import asyncio
import random
import struct
import time
import argparse
import sys

# São Paulo city only (município) – inland box to avoid sea/reservoirs
# (lat_min, lat_max, lon_min, lon_max)
LAND_BOXES = [
    (-23.88, -23.40, -46.82, -46.38),   # Município de São Paulo, land only (no coast)
]

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
        # IMEI 15 digits -> 8 bytes BCD-like (first nibble 0)
        imei_hex = "0" + imei
        imei_bytes = bytes.fromhex(imei_hex)
        # Content: IMEI (8) + Model ID (2) + Timezone (2)
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
        if lat > 0: flags |= 0x0400 # North
        if lon < 0: flags |= 0x0800 # West
        flags |= 0x1000 # Valid
        flags |= 0x4000 # Bit 14 set
        if ignition:
            flags |= 0x8000 # Bit 15 set
            
        content = date_time + struct.pack(">BIIBH", 
                                          satellites,
                                          lat_val,
                                          lon_val,
                                          int(speed),
                                          flags)
        return GT06Protocol.build_packet(GT06Protocol.MSG_LOCATION, content, serial)

def random_land_position():
    """Pick a random point on land (within one of the LAND_BOXES)."""
    box = random.choice(LAND_BOXES)
    south, north, west, east = box
    lat = random.uniform(south, north)
    lon = random.uniform(west, east)
    return lat, lon, box


class Device:
    def __init__(self, imei):
        self.imei = imei
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
                # 5 second timeout for connection
                reader, self.writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout=5.0)
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
                # Small wait for server to process login
                await asyncio.sleep(0.05)

            location = GT06Protocol.build_location(self.lat, self.lon, self.speed, self.course, self.ignition, self.serial)
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

async def main():
    parser = argparse.ArgumentParser(description="GT06 Load Test Script")
    parser.add_argument("--host", default="localhost", help="Server host")
    parser.add_argument("--port", type=int, default=5023, help="Server port")
    parser.add_argument("--devices", type=int, default=100, help="Number of devices (sends one report per device)")
    parser.add_argument("--reports", type=int, default=None, help="Ignored (one report per device); kept for compatibility")
    parser.add_argument("--interval", type=float, default=2.0, help="Interval cycle per worker (sec); higher = gentler on server")
    parser.add_argument("--concurrency", type=int, default=20, help="Max concurrent workers (keep low to avoid overwhelming server)")
    parser.add_argument("--close", action="store_true", help="Close connection after each report (use persistent conns for less load)")
    parser.add_argument("--max-rps", type=float, default=None, help="Cap reports per second (e.g. 50); avoids DOS")
    parser.add_argument("--imei-file", default="load-test-imeis.txt", help="File to save/load IMEIs (one per line); reuse on restart")
    args = parser.parse_args()

    if args.concurrency > 100:
        print("Warning: --concurrency > 100 can overwhelm the server. Consider 20-50.")
    if args.close and args.concurrency > 50:
        print("Warning: --close with high concurrency opens many short-lived connections. Consider concurrency <= 30.")

    # Load or generate IMEIs (persist to file so restart reuses same devices)
    imei_file = args.imei_file
    try:
        with open(imei_file, "r") as f:
            imeis = [line.strip() for line in f if line.strip()]
    except FileNotFoundError:
        imeis = []
    if imeis:
        print(f"Loaded {len(imeis)} IMEIs from {imei_file} (restart mode)")
        devices = [Device(imei) for imei in imeis]
    else:
        print(f"Generating {args.devices} IMEIs and saving to {imei_file}...")
        # Realistic 15-digit IMEIs: Prefix + Sequential (shifted) + Random (last 5); up to 1M without overlap
        imeis = [f"{358484000000000 + (i * 100000) + random.randint(0, 99999):015d}" for i in range(args.devices)]
        with open(imei_file, "w") as f:
            f.write("\n".join(imeis) + "\n")
        devices = [Device(imei) for imei in imeis]
    num_devices = len(devices)
    print(f"Using {num_devices} devices.")

    semaphore = asyncio.Semaphore(args.concurrency)
    report_count = 0
    fail_count = 0
    device_index = 0
    device_index_lock = asyncio.Lock()
    start_time = time.time()

    # Backoff on failure: global cooldown so ALL workers pause together (no flood of retries)
    backoff_lock = asyncio.Lock()
    cooldown_until = [0.0]  # timestamp; workers wait until this before next connect
    FAIL_PAUSE_SECONDS = 15
    consecutive_fails = [0]
    PAUSE_AFTER_CONSECUTIVE_FAILS = 10
    LONG_PAUSE_SECONDS = 300  # 5 minutes

    # Optional rate cap (reports per second)
    rate_limit_lock = asyncio.Lock() if args.max_rps else None
    min_interval = 1.0 / args.max_rps if args.max_rps else 0.0
    last_send_time = [0.0]

    # One report per device, once (total = num_devices)
    total_reports = num_devices

    async def worker(worker_id):
        nonlocal report_count, fail_count, device_index
        while device_index < num_devices:
            async with device_index_lock:
                idx = device_index
                device_index += 1

            device = devices[idx]

            async with semaphore:
                # Wait for global cooldown (so we actually pause after failures)
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

                if await device.connect(args.host, args.port):
                    if await device.send_report(close_after=args.close):
                        report_count += 1
                        consecutive_fails[0] = 0
                        if report_count % 1000 == 0:
                            elapsed = time.time() - start_time
                            print(f"Sent {report_count}/{total_reports} reports (1 per device)... ({report_count/elapsed:.2f} rps)")
                    else:
                        fail_count += 1
                        async with backoff_lock:
                            consecutive_fails[0] += 1
                            if consecutive_fails[0] >= PAUSE_AFTER_CONSECUTIVE_FAILS:
                                print(f"{PAUSE_AFTER_CONSECUTIVE_FAILS} consecutive failures: pausing {LONG_PAUSE_SECONDS // 60} min, then auto-resuming...")
                                cooldown_until[0] = time.time() + LONG_PAUSE_SECONDS
                                consecutive_fails[0] = 0
                            else:
                                print(f"Failure: pausing {FAIL_PAUSE_SECONDS}s (fail #{fail_count}, consecutive {consecutive_fails[0]})")
                                cooldown_until[0] = time.time() + FAIL_PAUSE_SECONDS
                        await asyncio.sleep(max(0, cooldown_until[0] - time.time()))
                else:
                    fail_count += 1
                    async with backoff_lock:
                        consecutive_fails[0] += 1
                        if consecutive_fails[0] >= PAUSE_AFTER_CONSECUTIVE_FAILS:
                            print(f"{PAUSE_AFTER_CONSECUTIVE_FAILS} consecutive failures: pausing {LONG_PAUSE_SECONDS // 60} min, then auto-resuming...")
                            cooldown_until[0] = time.time() + LONG_PAUSE_SECONDS
                            consecutive_fails[0] = 0
                        else:
                            print(f"Failure: pausing {FAIL_PAUSE_SECONDS}s (fail #{fail_count}, consecutive {consecutive_fails[0]})")
                            cooldown_until[0] = time.time() + FAIL_PAUSE_SECONDS
                    await asyncio.sleep(max(0, cooldown_until[0] - time.time()))

                if (report_count + fail_count) % 1000 == 0 and fail_count > 0:
                    print(f"Warning: {fail_count} failed connection/send attempts so far.")

            # Staggering
            await asyncio.sleep(args.interval / args.concurrency)

    print(f"Targeting: {args.host}:{args.port}")
    print(f"One report per device: {total_reports} total")
    print(f"Concurrency: {args.concurrency} workers")
    print(f"Mode: {'Connection-per-report' if args.close else 'Persistent-connections'}")
    if args.max_rps:
        print(f"Rate cap: {args.max_rps} rps max")
    # Suggest ulimit for high concurrency
    if args.concurrency > 100:
        print("TIP: If you see many failures, lower --concurrency (e.g. 20-30) and avoid --close or use --max-rps.")

    workers = [asyncio.create_task(worker(i)) for i in range(args.concurrency)]
    await asyncio.gather(*workers)

    total_time = time.time() - start_time
    print(f"\nFinished.")
    print(f"Total Success: {report_count}")
    print(f"Total Failed:  {fail_count}")
    print(f"Duration:      {total_time:.2f}s")
    print(f"Final Rate:    {report_count/total_time:.2f} rps")

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nInterrupted by user.")
        sys.exit(0)
