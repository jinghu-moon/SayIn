from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSET_ROOT = ROOT / "android" / "app" / "src" / "main" / "assets"
MANIFEST = ROOT / "android" / "vad-artifacts.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    errors: list[str] = []
    for model in manifest.get("models", []):
        path = ASSET_ROOT / model["file"]
        if not path.is_file():
            errors.append(f"missing VAD model: {path}")
            continue
        if path.stat().st_size != int(model["bytes"]):
            errors.append(f"VAD model size mismatch: {path}")
        if sha256(path) != model["sha256"]:
            errors.append(f"VAD model SHA-256 mismatch: {path}")
        license_path = ASSET_ROOT / model["licenseFile"]
        if not license_path.is_file():
            errors.append(f"missing VAD license: {license_path}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"VAD assets verified: {len(manifest.get('models', []))} model(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
