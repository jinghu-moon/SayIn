"""Summarize benchmark JSONL using nearest-rank percentiles, no dependencies."""
import argparse
import collections
import gzip
import json
import math
import statistics
from pathlib import Path


def percentile(values, p):
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * p) - 1)] if values else None


def distribution(values):
    return {
        "n": len(values),
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "p99": percentile(values, 0.99),
        "max": max(values) if values else None,
    }


def summarize(path):
    contents = gzip.decompress(path.read_bytes()).decode("utf-8-sig") if path.suffix == ".gz" else path.read_text(encoding="utf-8-sig")
    rows = [json.loads(line) for line in contents.splitlines() if line.strip()]
    uploads = [r for r in rows if r["type"] == "upload"]
    assert all(r["verified"] for r in uploads), "unverified upload"
    assert len({(r["round"], r["mode"], r["file"]) for r in uploads}) == len(uploads), "duplicate upload"
    files = collections.defaultdict(list)
    for r in uploads:
        files[r["file"]].append(r)
    output = {"uploads": len(uploads), "bytes": sum(r["bytes"] for r in uploads),
              "errors": [r for r in rows if r["type"] == "error"], "files": {}, "modes": {}}
    for name, records in sorted(files.items()):
        modes = collections.defaultdict(list)
        for r in records:
            modes[r["mode"]].append(r["ms"])
        output["files"][name] = {"bytes": records[0]["bytes"], "modes": {
            mode: {"n": len(ms), "median_ms": statistics.median(ms), "min_ms": min(ms), "max_ms": max(ms),
                   "median_mbps": records[0]["bytes"] * 8 / (statistics.median(ms) * 1000)}
            for mode, ms in sorted(modes.items())}}
    for mode in sorted({r["mode"] for r in rows}):
        relevant = [r for r in rows if r["mode"] == mode]
        large_jobs = {(r["round"], r["job"]) for r in relevant if r["type"] == "upload" and r["bytes"] >= 10 * 1024 * 1024}
        rtts = [r for r in relevant if r["type"] == "rtt"]
        setup = [r["setup_ms"] for r in relevant if r["type"] == "setup"]
        output["modes"][mode] = {
            "setup_median_ms": statistics.median(setup) if setup else None,
            "idle_rtt_ms": distribution([r["rtt_ms"] for r in rtts if r["job"] == 0]),
            "load_rtt_ms": distribution([r["rtt_ms"] for r in rtts if r["job"] != 0]),
            "large_load_rtt_ms": distribution([r["rtt_ms"] for r in rtts if (r["round"], r["job"]) in large_jobs]),
            "quic": [r for r in relevant if r["type"] == "quic_stats"],
        }
    return output


def validate(summary, rounds, modes, files=None):
    expected = set(modes.split(","))
    assert len(expected) == len(modes.split(",")) and "" not in expected, "invalid expected modes"
    assert not summary["errors"], summary["errors"]
    assert set(summary["modes"]) == expected, "missing or unexpected transport modes"
    if files is not None:
        assert len(summary["files"]) == files, "missing sample files"
    assert summary["uploads"] == len(summary["files"]) * len(expected) * rounds, "incomplete dataset"
    for file in summary["files"].values():
        assert set(file["modes"]) == expected and all(mode["n"] == rounds for mode in file["modes"].values()), "missing repetitions"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--expect-rounds", type=int, help="Require a complete dataset with this many rounds")
    parser.add_argument("--expect-modes", default="wss,tls-split,quic-1,quic-2,quic-4", help="Exact comma-separated mode set; default is the initial five-mode experiment")
    parser.add_argument("--expect-files", type=int, help="Require this many distinct image files")
    args = parser.parse_args()
    summary = summarize(args.input)
    if args.expect_rounds:
        validate(summary, args.expect_rounds, args.expect_modes, args.expect_files)
    result = json.dumps(summary, ensure_ascii=False, indent=2)
    if args.output:
        args.output.write_text(result + "\n", encoding="utf-8")
    else:
        print(result)
