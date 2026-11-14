"""Validate Kotlin/OkHttp transfer, connection reuse and cancellation evidence."""
import argparse
import gzip
import json
import math
import statistics
from pathlib import Path


def distribution(values):
    ordered = sorted(values)
    assert ordered
    return {"n": len(ordered), "min": ordered[0], "median": statistics.median(ordered),
            "p95": ordered[math.ceil(len(ordered) * .95) - 1], "max": ordered[-1]}


def analyze(path, rounds, modes=None):
    content = gzip.decompress(path.read_bytes()).decode("utf-8-sig") if path.suffix == ".gz" else path.read_text(encoding="utf-8-sig")
    rows = [json.loads(line) for line in content.splitlines() if line.strip()]
    modes = modes or ["wss-split", "https-stream", "wss-split-60", "https-stream-60"]
    assert len(set(modes)) == len(modes)
    assert set(r["mode"] for r in rows) == set(modes)
    assert not any(r["type"] == "error" for r in rows)
    uploads = [r for r in rows if r["type"] == "upload"]
    assert len(uploads) == rounds * len(modes) * 8
    assert len({(r["mode"], r["round"], r["file"]) for r in uploads}) == len(uploads)
    def connections(mode):
        return 1 if mode.startswith("quic-") else 2
    assert all(r["verified"] and r["connections"] == connections(r["mode"]) for r in uploads)
    result = {"uploads": len(uploads), "image_bytes": sum(r["bytes"] for r in uploads),
              "rtts": sum(r["type"] == "rtt" for r in rows), "modes": {}}
    for mode in modes:
        relevant = [r for r in rows if r["mode"] == mode]
        complete = [r for r in relevant if r["type"] == "upload"]
        cancels = [r for r in relevant if r["type"] == "cancel"]
        setups = [r for r in relevant if r["type"] == "setup"]
        assert len(setups) == rounds and all(r["connections"] == connections(mode) for r in setups)
        if mode.startswith("quic-"):
            streams = int(mode.removesuffix("-60").split("-")[-1])
            assert streams in (1, 2, 4) and all(r["streams"] == streams for r in setups + complete)
        assert {r["round"] for r in cancels} == set(range(1, rounds + 1)) and len(cancels) == rounds
        assert all(0 < r["accepted_bytes"] < 58658641 and r["accepted_bytes"] == r["accepted_bytes_after_300ms"]
                   and r["no_completion_after_cancel"] and r["local_producer_stop_ms"] >= 0 for r in cancels)
        per_round = {}
        for number in range(1, rounds + 1):
            large_jobs = {r["job"] for r in complete if r["round"] == number and r["bytes"] >= 10 * 1024 * 1024}
            assert len([r for r in complete if r["round"] == number]) == 8
            per_round[str(number)] = distribution([r["rtt_ms"] for r in relevant if r["type"] == "rtt" and r["round"] == number and r["job"] in large_jobs])
        result["modes"][mode] = {
            "large_rtt_per_round_ms": per_round,
            "cancel_local_ms": distribution([r["local_producer_stop_ms"] for r in cancels]),
            "cancel_ack_ms": distribution([r["server_cancel_ack_ms"] for r in cancels]),
            "sampled_memory_bytes": {key: distribution([r["memory"][key] for r in complete]) for key in complete[0]["memory"]},
        }
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--rounds", type=int, default=4)
    parser.add_argument("--modes", help="Exact comma-separated mode set; default is the historical four-mode experiment")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.write_text(json.dumps(analyze(args.input, args.rounds, args.modes.split(',') if args.modes else None), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
