"""Verify the maintained Yue/Kit/backend graph, with no registry substitutes."""
import json
import hashlib
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
YUE = ROOT.parent / "Yue-GPUI-Component"
SOURCE_RECORD = ROOT / "desktop/yue-source.json"


def source_snapshot():
    def git(*args):
        return subprocess.check_output(["git", *args], cwd=YUE)
    digest = hashlib.sha256()
    paths = sorted(git("ls-files", "-z", "--", "Cargo.toml", "Cargo.lock", "crates", "vendor").split(b"\0"))
    count = 0
    for path in paths:
        if not path:
            continue
        filename = YUE / path.decode("utf-8")
        if not filename.is_file():
            raise RuntimeError(f"Referenced source file is missing: {filename}")
        contents = filename.read_bytes()
        if filename.suffix.lower() in {".rs", ".toml", ".lock", ".json", ".svg", ".md", ".txt", ".hlsl", ".wgsl", ".css", ".html"}:
            contents = contents.replace(b"\r\n", b"\n")
        digest.update(path + b"\0" + hashlib.sha256(contents).digest())
        count += 1
    return {"snapshot_format": 1, "text_line_endings": "LF", "revision": git("rev-parse", "HEAD").decode().strip(),
            "tracked_source_files": count, "sha256": digest.hexdigest()}


def check():
    result = subprocess.run(
        ["cargo", "metadata", "--locked", "--offline", "--filter-platform",
         "x86_64-pc-windows-msvc", "--format-version", "1"],
        cwd=ROOT / "desktop", capture_output=True, text=True, encoding="utf-8",
    )
    if result.returncode:
        raise RuntimeError(result.stderr)
    metadata = json.loads(result.stdout)
    expected = {
        "gpui-kit": ("0.8.0", None),
        "gpui-base": ("0.8.0", YUE / "vendor/gpui-base"),
        "gpui-pre": ("0.3.9", YUE / "vendor/gpui-pre"),
        "gpui-pre-windows": ("0.3.9", YUE / "vendor/gpui-pre-windows"),
        "yue-component": ("0.1.0", YUE / "crates/yue-component"),
    }
    nodes = {node["id"]: node for node in metadata["resolve"]["nodes"]}
    active = [package for package in metadata["packages"] if package["id"] in nodes]
    for name, (version, directory) in expected.items():
        found = [package for package in active if package["name"] == name]
        assert len(found) == 1, f"Expected exactly one {name}, got {len(found)}"
        package = found[0]
        assert package["version"] == version, f"Unexpected {name}: {package['version']}"
        if directory:
            assert Path(package["manifest_path"]).resolve() == (directory / "Cargo.toml").resolve(), f"{name} patch bypassed"
            assert package["source"] is None, f"{name} must use local source"
        if name == "gpui-kit":
            forbidden = {"component", "assets", "default", "fast"} & set(nodes[package["id"]]["features"])
            assert not forbidden, f"Unexpected Kit features: {forbidden}"
    # Metadata contains weak optional edges that Cargo does not actually build
    # (notably Base's gpui-fast?/test-support). Check the compiled target graph.
    tree = subprocess.check_output(
        ["cargo", "tree", "--locked", "--offline", "--target", "x86_64-pc-windows-msvc",
         "--prefix", "none", "--format", "{p}", "--edges", "normal,build,dev"],
        cwd=ROOT / "desktop", text=True, encoding="utf-8",
    )
    compiled = {line.split()[0] for line in tree.splitlines() if line.strip()}
    assert not ({"gpui", "gpui-component", "gpui-fast"} & compiled), "Parallel GPUI backend or styled components found"
    print("Desktop dependency graph: Yue + Kit 0.8.0; all three local patches active; no parallel backend.")
    snapshot = source_snapshot()
    if "--record-source" in sys.argv:
        SOURCE_RECORD.write_text(json.dumps(snapshot, indent=2) + "\n", encoding="utf-8")
        print("Recorded current Yue source snapshot; rebuild and verify before accepting an update.")
    else:
        assert json.loads(SOURCE_RECORD.read_text(encoding="utf-8")) == snapshot, "Yue source changed; review, rebuild, test, then explicitly record the new snapshot"
        print("Referenced Yue source matches the recorded snapshot.")


if __name__ == "__main__":
    try:
        check()
    except (AssertionError, RuntimeError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
