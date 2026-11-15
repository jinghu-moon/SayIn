"""Build an allowlisted, reproducible development module. Never installs on a device."""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import struct
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MODULE_FILES = (
    "module.prop", "customize.sh", "service.sh", "action.sh", "uninstall.sh",
    "skip_mount", "app-policy.json", "config/defaults.json",
)
NOTICE = """SayIn Helper native clipboard collector

SayIn source: MIT; see LICENSE.
Built using Android NDK 29.0.14206865. The statically linked libc++ runtime retains
the LLVM Apache-2.0 WITH LLVM-exception license. See licenses/NDK-NOTICE.txt.
System libbinder_ndk and libcrypto are dynamically used, not redistributed.
No PathGuard-Next, SvcHub, Zygisk or Hook engine code is included.
"""


def checked_bytes(root: Path, relative: str) -> bytes:
    path = root / relative
    # Reject linked inputs rather than accidentally archiving data outside the source tree.
    for item in (path, *path.parents):
        if item.is_symlink():
            raise ValueError(f"Linked package input: {relative}")
        if item == root:
            break
    if not path.resolve().is_relative_to(root.resolve()):
        raise ValueError(f"Package input escapes root: {relative}")
    return path.read_bytes()


def validate_elf(data: bytes) -> None:
    if len(data) < 64 or len(data) > 16 * 1024 * 1024 or data[:7] != b"\x7fELF\x02\x01\x01":
        raise ValueError("Expected bounded little-endian ELF64")
    elf_type, machine = struct.unpack_from("<HH", data, 16)
    if elf_type != 3 or machine != 183:
        raise ValueError("Expected arm64 position-independent executable")


def collect_payload(repo: Path) -> dict[str, bytes]:
    template = repo / "root-helper/module"
    payload = {name: checked_bytes(template, name) for name in MODULE_FILES}
    for name, content in payload.items():
        text = content.decode("utf-8")
        if "\r" in text or text.startswith("\ufeff"):
            raise ValueError(f"Module text must be UTF-8 without BOM and use LF: {name}")
    properties = dict(line.split("=", 1) for line in payload["module.prop"].decode().splitlines())
    if properties["id"] != "sayin_helper" or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+-dev", properties["version"]):
        raise ValueError("Unexpected module identity/version")
    defaults = json.loads(payload["config/defaults.json"])
    if defaults != {"schema": 1, "clipboard_enabled": False, "keepalive_enabled": False}:
        raise ValueError("Installation defaults cannot enable collection or keepalive")
    policy = json.loads(payload["app-policy.json"])
    if policy != {"schema": 1, "application_id": "seeyuer.sayin",
                  "allowed_signing_certificate_sha256": [], "ipc_protocol": None}:
        raise ValueError("App IPC must remain unavailable until signature admission is implemented")
    binary = checked_bytes(repo, "root-helper/build/package/sayin-helper")
    validate_elf(binary)
    payload["bin/sayin-helper"] = binary
    payload["LICENSE"] = checked_bytes(repo, "LICENSE")
    payload["licenses/Apache-2.0.txt"] = checked_bytes(repo, "licenses/Apache-2.0.txt")
    payload["THIRD_PARTY_NOTICES.txt"] = NOTICE.encode()
    payload["licenses/NDK-NOTICE.txt"] = checked_bytes(repo, "root-helper/licenses/NDK-NOTICE.txt")
    manifest = {
        "schema": 1, "module_id": properties["id"], "version": properties["version"],
        "stage": "clipboard_collector", "target_abi": "arm64-v8a", "supported_api": [33, 37],
        "language": "C++20", "ndk": "29.0.14206865",
        "capabilities": {"clipboard_collection": True, "app_keepalive": False, "app_ipc": False},
        "sha256": {name: hashlib.sha256(data).hexdigest() for name, data in sorted(payload.items())},
    }
    payload["build-manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
    payload["SHA256SUMS"] = "".join(
        f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in sorted(payload.items())
    ).encode()
    return payload


def zip_bytes(payload: dict[str, bytes]) -> bytes:
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(payload.items()):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            mode = 0o755 if name.endswith(".sh") or name.startswith("bin/") else 0o644
            entry.external_attr = (stat.S_IFREG | mode) << 16
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)
    return stream.getvalue()


def atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=f".{path.name}-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def package(repo: Path) -> Path:
    payload = collect_payload(repo)
    version = json.loads(payload["build-manifest.json"])["version"]
    target = repo / f"root-helper/build/distributions/sayin-helper-{version}-arm64.zip"
    data = zip_bytes(payload)
    atomic_write(target, data)
    atomic_write(target.with_suffix(".zip.sha256"),
                 f"{hashlib.sha256(data).hexdigest()}  {target.name}\n".encode())
    return target


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    try:
        print(package(ROOT))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Module packaging failed: {error}\n")


if __name__ == "__main__":
    main()
