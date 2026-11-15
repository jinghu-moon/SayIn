"""Summarize synthetic benchmark JSON. Quality requires reviewing the saved translations."""
import argparse
import json
import math
from pathlib import Path
import re


def percentile(values, p):
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * p) - 1)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    summary = []
    for path in sorted(args.directory.glob("*.json")):
        report = json.loads(path.read_text(encoding="utf-8-sig"))
        if "samples" not in report:
            continue
        rows = report["samples"]
        cold = rows[0]
        warm = [r for r in rows if r["id"].startswith("warm-")]
        quality = rows[:14]
        memory = re.search(r"VmHWM:\s+(\d+) kB", report.get("procStatus", ""))
        cancellation_path = args.directory / f'cancellation-{report["model"]}.json'
        cancellation = json.loads(cancellation_path.read_text(encoding="utf-8-sig")) if cancellation_path.exists() else None
        result = {
            "model": report["model"], "device": report["device"], "passed": report.get("passed", False),
            "verifyMs": cold["verifyMs"], "loadMs": cold["loadMs"],
            "firstSentenceFirstMs": cold["firstUs"] / 1000,
            "firstSentenceTotalMs": cold["totalUs"] / 1000,
            "warmSampleCount": len(warm),
            "warmFirstMs": {str(p): percentile([r["firstUs"] / 1000 for r in warm], p) for p in [0.5, 0.95, 0.99]},
            "warmTotalMs": {str(p): percentile([r["totalUs"] / 1000 for r in warm], p) for p in [0.5, 0.95, 0.99]},
            "qualitySampleCount": len(quality),
            "qualityCompleted": sum(r["end"] == "Complete" for r in quality),
            "processPeakRssMiB": int(memory.group(1)) / 1024 if memory else None,
            "maxPostRequestPssMiB": max(r["pssKb"] for r in rows) / 1024,
            "cancelMs": {r["id"]: r["cancelUs"] / 1000 for r in rows if "cancelUs" in r},
            "cancelMeasurement": "initial run includes PSS/JSON/report I/O; upper bound only",
            "releaseMs": report.get("releaseUs", 0) / 1000,
        }
        if cancellation is not None:
            assert cancellation["passed"] and cancellation["modelSha256"] == report["modelSha256"]
            result["cancelMs"] = {f"cancel-{name}": cancellation[f"{name}Us"] / 1000 for name in ["decode", "prefill", "load"]}
            result["cancelMeasurement"] = cancellation["measurement"]
        summary.append(result)
    print(json.dumps(summary, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
