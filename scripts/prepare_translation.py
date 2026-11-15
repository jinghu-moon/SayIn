"""Prepare pinned llama.cpp; audit local GGUFs without loading inference weights.

Only public artifacts are fetched. No microphone, application data or user text.
The complete source tree is checked, including when reusing a reference ZIP.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "android/translation-artifacts.json"
LOCAL = ROOT / "android/local-translation"


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def tree_sha(rows):
    return hashlib.sha256(json.dumps(sorted(rows), separators=(",", ":"), ensure_ascii=True).encode()).hexdigest()


def source(lock, archive_path=None, verify_only=False):
    target = LOCAL / "llama.cpp"
    if target.exists():
        rows = [[p.relative_to(target).as_posix(), p.stat().st_size, sha(p)]
                for p in sorted(target.rglob("*")) if p.is_file()]
        if tree_sha(rows) != lock["treeSha256"]:
            raise ValueError("llama.cpp source tree differs from the pinned artifact")
        print("llama.cpp source verified:", lock["revision"])
        return
    if verify_only:
        raise ValueError("Prepare llama.cpp first: python scripts/prepare_translation.py [--source-archive ZIP]")
    LOCAL.mkdir(parents=True, exist_ok=True)
    if archive_path is None:
        archive_path = LOCAL / "llama-source.zip"
        with urllib.request.urlopen(lock["archiveUrl"], timeout=60) as response, archive_path.open("wb") as output:
            while block := response.read(1024 * 1024):
                output.write(block)
    with zipfile.ZipFile(archive_path) as archive:
        rows = []
        entries = []
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            relative = Path(entry.filename.split("/", 1)[1])
            if relative.is_absolute() or ".." in relative.parts or ":" in str(relative):
                raise ValueError("Unsafe source archive")
            data = archive.read(entry)
            rows.append([relative.as_posix(), len(data), hashlib.sha256(data).hexdigest()])
            entries.append((entry, relative))
        if tree_sha(rows) != lock["treeSha256"]:
            raise ValueError("Unrecognized llama.cpp source archive")
        # Validation precedes extraction; partial extraction is never accepted by the build check.
        for entry, relative in entries:
            path = target / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(archive.read(entry))
    print("llama.cpp prepared:", lock["revision"], len(rows), "files")


def gguf_metadata(path):
    with path.open("rb") as stream:
        def unpack(fmt):
            size = struct.calcsize(fmt)
            data = stream.read(size)
            if len(data) != size:
                raise ValueError("Truncated GGUF metadata")
            return struct.unpack(fmt, data)[0]

        def string(keep=True):
            size = unpack("<Q")
            if size > 32 * 1024 * 1024:
                raise ValueError("Oversized GGUF string")
            if keep:
                data = stream.read(size)
                if len(data) != size:
                    raise ValueError("Truncated GGUF string")
                return data.decode("utf-8", errors="strict")
            stream.seek(size, 1)

        def value(kind, keep):
            formats = {0: "<B", 1: "<b", 2: "<H", 3: "<h", 4: "<I", 5: "<i", 6: "<f", 7: "<?", 10: "<Q", 11: "<q", 12: "<d"}
            if kind == 8:
                return string(keep)
            if kind == 9:
                element, count = unpack("<I"), unpack("<Q")
                if count > 2_000_000 or element == 9:
                    raise ValueError("Unbounded GGUF array")
                for _ in range(count):
                    value(element, False)
                return {"elementType": element, "count": count}
            if kind not in formats:
                raise ValueError("Unknown GGUF type")
            return unpack(formats[kind])

        if stream.read(4) != b"GGUF" or unpack("<I") != 3:
            raise ValueError("Expected GGUF v3")
        tensors, count = unpack("<Q"), unpack("<Q")
        if not (0 < tensors < 100_000 and 0 < count < 10_000):
            raise ValueError("Invalid GGUF counts")
        metadata = {"tensorCount": tensors}
        for _ in range(count):
            name = string()
            item = value(unpack("<I"), True)
            if name == "tokenizer.chat_template":
                metadata["chatTemplateSha256"] = hashlib.sha256(item.encode()).hexdigest()
            elif name.startswith(("general.", "tokenizer.ggml.")) or name.endswith(("context_length", "block_count", "embedding_length")):
                metadata[name] = item
        if stream.tell() >= path.stat().st_size:
            raise ValueError("Missing tensor data")
        return metadata


def audit_models(lock, directory, verify_upstream):
    report = []
    for model in lock["models"]:
        path = directory / model["file"]
        if path.stat().st_size != model["bytes"] or sha(path) != model["sha256"]:
            raise ValueError("Model integrity failure: " + model["id"])
        metadata = gguf_metadata(path)
        if metadata["general.architecture"] != model["architecture"] or metadata["general.file_type"] != 15:
            raise ValueError("Unexpected model architecture/quantization")
        if verify_upstream:
            url = f'https://huggingface.co/{model["repository"]}/raw/{model["revision"]}/{model["file"]}'
            with urllib.request.urlopen(url, timeout=30) as response:
                pointer = response.read(4096).decode()
            if f'oid sha256:{model["sha256"]}\n' not in pointer or f'size {model["bytes"]}\n' not in pointer:
                raise ValueError("Official LFS identity mismatch")
        report.append({"id": model["id"], "bytes": model["bytes"], "sha256": model["sha256"], "officialLfsVerified": verify_upstream, "metadata": metadata})
        print("model verified:", model["id"], model["bytes"], metadata["chatTemplateSha256"], flush=True)
    out = LOCAL / "model-audit.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-archive", type=Path)
    parser.add_argument("--verify-source", action="store_true")
    parser.add_argument("--models-dir", type=Path)
    parser.add_argument("--verify-upstream", action="store_true")
    args = parser.parse_args()
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    source(lock["llama"], args.source_archive, args.verify_source)
    if args.models_dir:
        audit_models(lock, args.models_dir, args.verify_upstream)


if __name__ == "__main__":
    main()
