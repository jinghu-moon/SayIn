"""Opt-in bounded TCP relay for isolated LAN fixtures; never terminates TLS.

Each direction of each connection is independently paced. This is controlled
software backpressure, not a Wi-Fi loss/RTT simulator or a production proxy.
No payload, certificates, tokens or peer addresses are written to the report.
"""
import argparse
import asyncio
import json
import socket
import time
from pathlib import Path


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--address", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--rate", type=int, default=128 * 1024)
    args = parser.parse_args()
    if not 1024 <= args.rate <= 1024 * 1024 or not 1 <= args.port <= 65535:
        raise ValueError("invalid bounded relay configuration")
    # Require the fixture's exact LAN IPv4; no wildcard or localhost listener.
    import ipaddress
    address = ipaddress.IPv4Address(args.address)
    if address.is_unspecified or address.is_loopback or address.is_multicast:
        raise ValueError("an explicit fixture LAN IPv4 is required")
    args.directory.mkdir(exist_ok=False)
    tasks = set()
    records = []
    rejected = 0
    active = 0
    started = time.monotonic()

    async def relay(reader, writer, direction):
        count = 0
        begin = time.monotonic()
        try:
            while data := await asyncio.wait_for(reader.read(4096), 30):
                # At most 4KiB buffered per direction, plus asyncio's bounded
                # stream buffer. Additional per-write delay is explicitly
                # reported; it is not described as a measured network RTT.
                await asyncio.sleep(len(data) / args.rate + 0.010)
                writer.write(data)
                await asyncio.wait_for(writer.drain(), 30)
                count += len(data)
        finally:
            records.append({"direction": direction, "bytes": count,
                            "elapsed_seconds": time.monotonic() - begin})

    async def accept(reader, writer):
        nonlocal active, rejected
        task = asyncio.current_task()
        tasks.add(task)
        upstream = None
        streams = []
        try:
            if active >= 4:
                rejected += 1
                return
            active += 1
            try:
                upstream_reader, upstream = await asyncio.wait_for(
                    asyncio.open_connection(args.address, args.port, limit=16384,
                                            family=socket.AF_INET,
                                            flags=socket.AI_NUMERICHOST), 5)
                streams = [asyncio.create_task(relay(reader, upstream, "up")),
                           asyncio.create_task(relay(upstream_reader, writer, "down"))]
                done, _ = await asyncio.wait(streams, timeout=600,
                                             return_when=asyncio.FIRST_COMPLETED)
                for finished in done:
                    finished.result()
            except (OSError, asyncio.TimeoutError):
                # Cancellation/disconnects are expected in pause probes. The
                # product's own receipt assertions determine test success.
                pass
            finally:
                active -= 1
        finally:
            for stream in streams:
                stream.cancel()
            await asyncio.gather(*streams, return_exceptions=True)
            for connection in [writer, upstream]:
                if connection is not None:
                    connection.close()
                    try:
                        await connection.wait_closed()
                    except (OSError, asyncio.CancelledError):
                        pass
            tasks.discard(task)

    server = await asyncio.start_server(accept, args.address, 0, limit=16384,
                                        backlog=4, family=socket.AF_INET,
                                        flags=socket.AI_NUMERICHOST)
    (args.directory / "ready.json").write_text(json.dumps({
        "port": server.sockets[0].getsockname()[1]}), encoding="utf-8")
    try:
        while not (args.directory / "stop").exists():
            if time.monotonic() - started > 660:
                raise TimeoutError("relay lifetime exceeded")
            await asyncio.sleep(0.1)
    finally:
        server.close()
        await server.wait_closed()
        for task in list(tasks):
            task.cancel()
        await asyncio.gather(*list(tasks), return_exceptions=True)
        (args.directory / "result.json").write_text(json.dumps({
            "scope": "LAN encrypted TCP pacing, independent per connection/direction; not actual poor Wi-Fi",
            "rate_bytes_per_second": args.rate, "chunk_bytes": 4096,
            "additional_delay_per_write_ms": 10, "max_connections": 4,
            "elapsed_seconds": time.monotonic() - started,
            "rejected_connections": rejected, "streams": records}, indent=2), encoding="utf-8")


if __name__ == "__main__":
    asyncio.run(main())
