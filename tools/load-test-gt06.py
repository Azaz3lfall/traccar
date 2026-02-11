#!/usr/bin/env python3

import asyncio
import random
import struct
import time
import argparse
import sys

# Sao Paulo (Brazil) Bounding Box
SP_NORTH = -19.78
SP_SOUTH = -25.31
SP_WEST = -53.11
SP_EAST = -44.16

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

class Device:
    def __init__(self, imei):
        self.imei = imei
        self.lat = random.uniform(SP_SOUTH, SP_NORTH)
        self.lon = random.uniform(SP_WEST, SP_EAST)
        self.speed = random.uniform(20, 100)
        self.course = random.randint(0, 359)
        self.ignition = random.choice([True, False])
        self.serial = 1
        self.logged_in = False
        self.writer = None

    def move(self):
        self.lat += 0.0001
        self.lon += 0.0001
        self.speed = max(0, min(120, self.speed + random.uniform(-5, 5)))
        self.ignition = random.choice([True, False]) if random.random() < 0.05 else self.ignition

    async def connect(self, host, port):
        if self.writer is None:
            try:
                # 5 second timeout for connection
                reader, self.writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout=5.0)
                self.logged_in = False
                return True
            except Exception:
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
        except Exception:
            self.disconnect()
            return False

async def main():
    parser = argparse.ArgumentParser(description="GT06 Load Test Script")
    parser.add_argument("--host", default="localhost", help="Server host")
    parser.add_argument("--port", type=int, default=5023, help="Server port")
    parser.add_argument("--devices", type=int, default=100, help="Number of devices")
    parser.add_argument("--interval", type=float, default=1.0, help="Total interval cycle (sec)")
    parser.add_argument("--reports", type=int, default=1000, help="Total reports to send")
    parser.add_argument("--concurrency", type=int, default=50, help="Max concurrent workers")
    parser.add_argument("--close", action="store_true", help="Close connection after each report")
    args = parser.parse_args()

    print(f"Generating {args.devices} devices...")
    # Generate realistic 15-digit IMEIs: Prefix + Sequential (shifted left) + Random (last 5)
    # This handles up to 1M devices without overlaps
    devices = [Device(f"{358484000000000 + (i * 100000) + random.randint(0, 99999):015d}") for i in range(args.devices)]

    semaphore = asyncio.Semaphore(args.concurrency)
    report_count = 0
    fail_count = 0
    device_index = 0
    device_index_lock = asyncio.Lock()
    start_time = time.time()

    async def worker(worker_id):
        nonlocal report_count, fail_count, device_index
        while report_count < args.reports:
            async with device_index_lock:
                idx = device_index
                device_index += 1
            
            device = devices[idx % args.devices]
            
            async with semaphore:
                if await device.connect(args.host, args.port):
                    if await device.send_report(close_after=args.close):
                        report_count += 1
                        if report_count % 1000 == 0:
                            elapsed = time.time() - start_time
                            print(f"Sent {report_count}/{args.reports} reports... ({report_count/elapsed:.2f} rps)")
                    else:
                        fail_count += 1
                else:
                    fail_count += 1
                
                if (report_count + fail_count) % 1000 == 0 and fail_count > 0:
                    print(f"Warning: {fail_count} failed connection/send attempts so far.")

            # Staggering
            await asyncio.sleep(args.interval / args.concurrency)

    print(f"Targeting: {args.host}:{args.port}")
    print(f"Concurrency: {args.concurrency} workers")
    print(f"Mode: {'Connection-per-report' if args.close else 'Persistent-connections'}")
    
    # Suggest ulimit for high concurrency
    if args.concurrency > 1000:
        print("TIP: If you see many failures, run 'ulimit -n 100000' in your terminal.")

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
