"""Verify packaged translation JNI, bundled weights, ABI, 16KB ELF/ZIP alignment and dependencies."""
import argparse
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk")
    args = parser.parse_args()
    readelf = args.sdk / "ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
    zipalign = args.sdk / "build-tools/37.0.0/zipalign.exe"
    subprocess.run([str(zipalign), "-c", "-P", "16", "4", str(args.apk)], check=True)
    with zipfile.ZipFile(args.apk) as archive, tempfile.TemporaryDirectory(prefix="sayin-elf-") as temp:
        names = set(archive.namelist())
        library = "lib/arm64-v8a/libsayin_translation.so"
        assert library in names, "Translation JNI missing"
        assert all("arm64-v8a" in n for n in names if n.startswith("lib/") and n.endswith(".so")), "Unexpected ABI"
        catalog = json.loads(archive.read("assets/translation-artifacts.json"))
        bundled = [model for model in catalog["models"] if model.get("bundled")]
        assert bundled, "No bundled translation model"
        expected = {f"assets/translation/{model['file']}" for model in bundled}
        actual = {name for name in names if name.endswith(".gguf")}
        assert actual == expected, f"Unexpected GGUF entries: {sorted(actual ^ expected)}"
        for model in bundled:
            name = f"assets/translation/{model['file']}"
            info = archive.getinfo(name)
            assert info.compress_type == zipfile.ZIP_STORED, f"Bundled model must be uncompressed: {name}"
            assert info.file_size == model["bytes"], f"Bundled model size mismatch: {name}"
            digest = hashlib.sha256()
            with archive.open(info) as stream:
                for block in iter(lambda: stream.read(1 << 20), b""):
                    digest.update(block)
            assert digest.hexdigest() == model["sha256"], f"Bundled model SHA-256 mismatch: {name}"
            print(f"Bundled translation model verified: {name} ({info.file_size} bytes)")
        for notice in ["THIRD-PARTY-NOTICES.txt", "llama.cpp-MIT.txt", "Hy-MT2-Apache-2.0.txt", "PLaMo-Community-License.txt", "Android-NDK-29-toolchain-NOTICE.txt"]:
            assert f"assets/licenses/translation/{notice}" in names, f"Missing translation notice: {notice}"
        packaged = {Path(n).name for n in names if n.startswith("lib/") and n.endswith(".so")}
        system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so"}
        for name in sorted(n for n in names if n.startswith("lib/") and n.endswith(".so")):
            path = Path(temp) / Path(name).name
            path.write_bytes(archive.read(name))
            headers = subprocess.check_output([str(readelf), "-lW", str(path)], text=True)
            loads = [line for line in headers.splitlines() if line.strip().startswith("LOAD ")]
            assert loads and all(int(line.split()[-1], 16) >= 16384 for line in loads), f"16KB ELF alignment: {name}"
            dynamic = subprocess.check_output([str(readelf), "-dW", str(path)], text=True)
            needed = set(re.findall(r"Shared library: \[(.*?)\]", dynamic))
            assert needed <= packaged | system, f"Unpackaged dependencies in {name}: {needed - packaged - system}"
            if Path(name).name == "libc++_shared.so":
                # AGP strips debug sections, so compare the ELF build identity rather than file bytes.
                runtime = args.sdk / "ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
                def build_id(binary):
                    notes = subprocess.check_output([str(readelf), "-n", str(binary)], text=True)
                    return re.search(r"Build ID: ([0-9a-f]+)", notes).group(1)
                assert build_id(path) == build_id(runtime), "Unexpected C++ runtime provenance"
            if name == library:
                exports = subprocess.check_output([str(readelf), "--dyn-syms", "--wide", str(path)], text=True)
                for method in ["create", "reset", "cancel", "load", "loadFd", "run", "destroy"]:
                    assert f"Java_seeyuer_sayin_translation_NativeBindings_{method}" in exports, f"Missing JNI: {method}"
                assert not any("llama_" in line and "GLOBAL" in line and " UND " not in line for line in exports.splitlines()), "Static llama symbols leaked"
                print("JNI exports, CPU dependency closure, ELF alignment verified:", name)
        print("APK ZIP 16KB alignment, all native ELF alignments, arm64 ABI, dependency closure verified:", args.apk)
        print("Bundled translation weights are stored uncompressed and match the pinned manifest.")


if __name__ == "__main__":
    main()
