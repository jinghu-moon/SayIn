from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSET_ROOT = ROOT / "android" / "app" / "src" / "main" / "assets" / "punctuation"
MANIFEST = ROOT / "android" / "punctuation-artifacts.json"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    errors: list[str] = []
    for name, bytes_expected in manifest["files"].items():
        path = ASSET_ROOT / name
        if not path.is_file():
            errors.append(f"missing punctuation asset: {path}")
            continue
        if path.stat().st_size != int(bytes_expected):
            errors.append(f"punctuation asset size mismatch: {name}")
        if sha256(path) != manifest["sha256"][name]:
            errors.append(f"punctuation asset SHA-256 mismatch: {name}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Punctuation assets verified: {len(manifest['files'])} file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
