"""Recompute nearest-rank quantiles; never combine fake/native or prototype runs."""
import json
import math
from pathlib import Path
from statistics import median

for path in sorted(Path(__file__).parent.glob("*-native.json")):
    data = json.loads(path.read_text(encoding="utf-8"))
    measurements = data["mobile"]["measurements"]
    assert len(measurements) == data["desktop"]["input_calls"] == 15
    assert data["desktop"]["native_target"]
    print(path.stem)
    for name in sorted({row["file"] for row in measurements}):
        rows = [row for row in measurements if row["file"] == name]
        assert len(rows) == 3
        print(name, {key: round(median(row[key] for row in rows), 1)
                     for key in ("prepare_ms", "send_to_receipt_ms", "total_ms")})
    for key in ("prepare_ms", "send_to_receipt_ms", "total_ms"):
        ordered = sorted(row[key] for row in measurements)
        print(key, {f"p{int(q * 100)}": round(ordered[math.ceil(len(ordered) * q) - 1], 1)
                    for q in (0.5, 0.95, 0.99)})
    assert len(data["mobile"]["rejections"]) == 3

for name in ("slow-link", "slow-link-soak"):
    data = json.loads((Path(__file__).parent / f"{name}.json").read_text(encoding="utf-8"))
    rows = data["mobile"]["measurements"]
    assert len(rows) == data["desktop"]["input_calls"] == (25 if name.endswith("soak") else 5)
    assert data["mobile"]["controlled_slow_link"] and not data["desktop"]["native_target"]
    assert data["relay"]["rejected_connections"] == 0
    values = sorted(row["send_to_receipt_ms"] for row in rows)
    print(name, {f"p{int(q*100)}": round(values[math.ceil(len(values)*q)-1], 2) for q in (.5, .95, .99)})

data = json.loads((Path(__file__).parent / "slow-pause-lan.json").read_text(encoding="utf-8"))
assert data["desktop"]["input_calls"] == 0 and len(data["mobile"]["measurements"]) == 5
for key in ("explicit_cancel_ms", "control_marker_to_paused_ms"):
    values = [row[key] for row in data["mobile"]["measurements"]]
    print(key, {"median": round(median(values), 2), "max": round(max(values), 2)})

for phase in ("before", "after"):
    data = json.loads((Path(__file__).parent / f"history-soak-{phase}.json").read_text(encoding="utf-8"))
    assert data["records"] == 30 and len(data["rounds"]) == 60
    rounds = data["process_memory"]["closed_rounds"]
    assert len(rounds) == 60
    early = median(row["median_private_bytes"] for row in rounds[:10])
    late = median(row["median_private_bytes"] for row in rounds[-10:])
    print("history", phase, {"early_private_mib": round(early/1048576, 2), "late_private_mib": round(late/1048576, 2)})
    if phase == "after":
        assert data["memory_guard"]["growth_bytes"] <= data["memory_guard"]["growth_limit_bytes"]
