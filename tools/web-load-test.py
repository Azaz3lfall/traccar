#!/usr/bin/env python3
"""
Async web load test: N simultaneous connections, loop HTTP GET to a configurable host:port/path.
Uses only stdlib (asyncio + urllib). Use for stressing web UI or API (e.g. port 8082). Ctrl+C to stop.
"""
from __future__ import annotations

import argparse
import asyncio
import signal
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor


def parse_args():
    p = argparse.ArgumentParser(description="Async web load test: N connections, loop GET to host:port (stdlib only)")
    p.add_argument("--host", default="127.0.0.1", help="Target host (default 127.0.0.1)")
    p.add_argument("--port", type=int, default=8082, help="Target port (default 8082)")
    p.add_argument("--connections", "-c", type=int, default=10, help="Simultaneous connections (default 10)")
    p.add_argument("--path", default="/", help="Request path (default /)")
    p.add_argument("--interval", type=float, default=0, help="Seconds between requests per worker (0 = no delay, default 0)")
    p.add_argument("--report-every", type=float, default=5, help="Print stats every N seconds (default 5)")
    p.add_argument("--timeout", type=float, default=30, help="Request timeout seconds (default 30)")
    return p.parse_args()


def fetch_sync(url: str, timeout: float) -> bool:
    """One blocking GET; returns True if status < 400."""
    try:
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            resp.read()
            return resp.status < 400
    except (urllib.error.HTTPError, OSError, TimeoutError):
        return False


async def worker(
    url: str,
    interval: float,
    timeout: float,
    stats: dict,
    shutdown: asyncio.Event,
    executor: asyncio.Executor,
) -> None:
    loop = asyncio.get_event_loop()
    while not shutdown.is_set():
        ok = await loop.run_in_executor(executor, fetch_sync, url, timeout)
        stats["requests"] += 1
        if not ok:
            stats["errors"] += 1
        if interval > 0:
            await asyncio.sleep(interval)
        else:
            await asyncio.sleep(0)


async def reporter(stats: dict, report_every: float, start_time: float, shutdown: asyncio.Event) -> None:
    while not shutdown.is_set():
        await asyncio.sleep(report_every)
        if shutdown.is_set():
            break
        elapsed = time.time() - start_time
        r = stats["requests"]
        e = stats["errors"]
        rps = r / elapsed if elapsed > 0 else 0
        print(f"  requests={r} errors={e} elapsed={elapsed:.1f}s rps={rps:.1f}")


async def main(args: argparse.Namespace) -> None:
    url = f"http://{args.host}:{args.port}{args.path}"
    shutdown = asyncio.Event()
    stats = {"requests": 0, "errors": 0}

    def on_sig(sig, frame):
        shutdown.set()

    signal.signal(signal.SIGINT, on_sig)
    try:
        signal.signal(signal.SIGTERM, on_sig)
    except (ValueError, OSError):
        pass

    print(f"Web load test: {args.connections} connections -> {url}")
    print(f"Interval={args.interval}s report_every={args.report_every}s (Ctrl+C to stop)\n")

    start_time = time.time()
    with ThreadPoolExecutor(max_workers=args.connections) as executor:
        workers = [
            asyncio.create_task(worker(url, args.interval, args.timeout, stats, shutdown, executor))
            for _ in range(args.connections)
        ]
        report_task = asyncio.create_task(reporter(stats, args.report_every, start_time, shutdown))
        await shutdown.wait()

    report_task.cancel()
    try:
        await report_task
    except asyncio.CancelledError:
        pass
    for t in workers:
        t.cancel()
    for t in workers:
        try:
            await t
        except asyncio.CancelledError:
            pass

    elapsed = time.time() - start_time
    r, e = stats["requests"], stats["errors"]
    rps = r / elapsed if elapsed > 0 else 0
    print(f"\nDone. requests={r} errors={e} elapsed={elapsed:.1f}s rps={rps:.1f}")


if __name__ == "__main__":
    args = parse_args()
    asyncio.run(main(args))
