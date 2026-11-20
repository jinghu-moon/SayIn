#!/usr/bin/env python3
"""Verify the bundled basellm snapshot against its manifest and license."""
from __future__ import annotations

import hashlib
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
ASSET = ROOT / "android" / "app" / "src" / "main" / "assets" / "basellm"
EXPECTED_SOURCE = "https://basellm.github.io/llm-metadata/api/all.json"


def fail(message: str) -> None:
    print(f"basellm snapshot: FAIL: {message}")
    raise SystemExit(1)


def main() -> None:
    manifest_path = ASSET / "manifest.json"
    data_path = ASSET / "all.json"
    license_path = ASSET / "LICENSE"
    for path in (manifest_path, data_path, license_path):
        if not path.is_file():
            fail(f"missing {path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    data = data_path.read_bytes()
    if manifest.get("formatVersion") != 1:
        fail("unsupported formatVersion")
    if manifest.get("source") != EXPECTED_SOURCE:
        fail("unexpected source URL")
    if manifest.get("license") != "Apache-2.0":
        fail("unexpected license")
    if not license_path.read_text(encoding="utf-8").strip():
        fail("empty upstream license")
    digest = hashlib.sha256(data).hexdigest()
    if digest != manifest.get("sha256"):
        fail(f"sha256 {digest} != {manifest.get('sha256')}")
    if len(data) != manifest.get("bytes"):
        fail("byte count mismatch")
    try:
        catalog = json.loads(data)
    except json.JSONDecodeError as error:
        fail(f"invalid JSON: {error}")
    for provider in ("deepseek", "alibaba-cn", "openai", "anthropic"):
        models = catalog.get(provider, {}).get("models", {})
        textual = [
            model for model in models.values()
            if "text" in model.get("modalities", {}).get("output", [])
            and model.get("status") != "deprecated"
        ]
        if not textual:
            fail(f"provider {provider} has no text model candidates")
    print(f"basellm snapshot: OK sha256={digest} bytes={len(data)} providers=deepseek,alibaba-cn,openai,anthropic")


if __name__ == "__main__":
    main()
