"""Host-only native package and manager-script tests, separate from device acceptance."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import struct
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("package_root_helper", ROOT / "scripts/package_root_helper.py")
PACK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACK)

def find_bash():
    candidate = Path("C:/A_Softwares/Git/bin/bash.exe")
    if candidate.is_file():
        return str(candidate)
    candidate = shutil.which("bash")
    return candidate if candidate and "system32" not in candidate.lower() else None

class EngineeringTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="sayin-native-package-")
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        for name in PACK.MODULE_FILES:
            target = self.repo / "root-helper/module" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / "root-helper/module" / name, target)
        for name in ("LICENSE", "licenses/Apache-2.0.txt", "root-helper/licenses/NDK-NOTICE.txt"):
            target = self.repo / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, target)
        self.binary = self.repo / "root-helper/build/package/sayin-helper"
        self.binary.parent.mkdir(parents=True, exist_ok=True)
        header = bytearray(64)
        header[:7] = b"\x7fELF\x02\x01\x01"
        struct.pack_into("<HH", header, 16, 3, 183)
        self.binary.write_bytes(header)

    def test_reproducible_whitelist_checksums_permissions(self):
        extra = self.repo / "root-helper/module/secret.env"
        extra.write_text("not for distribution")
        target = PACK.package(self.repo)
        first = target.read_bytes()
        self.assertEqual(first, PACK.package(self.repo).read_bytes())
        with zipfile.ZipFile(target) as z:
            self.assertNotIn("secret.env", z.namelist())
            self.assertNotIn("daemon.dex", z.namelist())
            for line in z.read("SHA256SUMS").decode().splitlines():
                digest, name = line.split("  ", 1)
                self.assertEqual(digest, hashlib.sha256(z.read(name)).hexdigest())
            for info in z.infolist():
                mode = 0o755 if info.filename.endswith(".sh") or info.filename.startswith("bin/") else 0o644
                self.assertEqual(mode, stat.S_IMODE(info.external_attr >> 16))
            self.assertEqual(json.loads(z.read("build-manifest.json"))["supported_api"], [33])
        self.assertTrue(target.with_suffix(".zip.sha256").read_text().startswith(hashlib.sha256(first).hexdigest()))

    def test_invalid_or_wrong_architecture_binary_rejected(self):
        for value in (b"", b"dex\n035\x00" + bytes(100), b"\x7fELF"+bytes(60)):
            self.binary.write_bytes(value)
            with self.assertRaises(ValueError): PACK.collect_payload(self.repo)

    def test_missing_binary_rejected(self):
        self.binary.unlink()
        with self.assertRaises(FileNotFoundError): PACK.collect_payload(self.repo)

    def test_failed_rebuild_keeps_previous_package(self):
        target = PACK.package(self.repo)
        before = target.read_bytes()
        (self.repo / "root-helper/module/service.sh").write_bytes(b"#!/system/bin/sh\r\n")
        with self.assertRaises(ValueError): PACK.package(self.repo)
        self.assertEqual(before, target.read_bytes())

    def test_default_opt_in_and_app_admission_cannot_be_bypassed(self):
        defaults = self.repo / "root-helper/module/config/defaults.json"
        original = defaults.read_bytes()
        for key in ("clipboard_enabled", "keepalive_enabled"):
            value = json.loads(original); value[key] = True
            defaults.write_text(json.dumps(value))
            with self.assertRaises(ValueError): PACK.collect_payload(self.repo)
        defaults.write_bytes(original)
        policy = self.repo / "root-helper/module/app-policy.json"
        value = json.loads(policy.read_bytes()); value["allowed_signing_certificate_sha256"] = ["00"*32]
        policy.write_text(json.dumps(value))
        with self.assertRaises(ValueError): PACK.collect_payload(self.repo)

    def test_scripts_parse(self):
        bash = find_bash()
        if not bash: self.skipTest("host Bash unavailable")
        for name in PACK.MODULE_FILES:
            if name.endswith(".sh"):
                result = subprocess.run([bash, "-n"], input=(self.repo / "root-helper/module" / name).read_bytes(), capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr)

    def install(self, api="33", arch="arm64", bootmode="true", corrupt=False):
        bash = find_bash()
        if not bash: self.skipTest("host Bash unavailable")
        target = PACK.package(self.repo)
        stage = self.repo / "stage"
        stage.mkdir(exist_ok=True)
        with zipfile.ZipFile(target) as z: z.extractall(stage)
        if corrupt: (stage / "bin/sayin-helper").write_bytes(b"bad")
        script = """
MODPATH="$PWD"
abort() { echo "$*" >&2; exit 2; }
ui_print() { echo "$*"; }
set_perm_recursive() { :; }
set_perm() { :; }
. ./customize.sh
"""
        return subprocess.run([bash, "-c", script], cwd=stage, env=dict(os.environ, API=api, ARCH=arch, BOOTMODE=bootmode), capture_output=True, text=True)

    def test_supported_installer_fixture(self):
        result = self.install()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("collection is OFF", result.stdout)

    def test_installer_rejects_unknown_api_arch_recovery_and_tamper(self):
        for args in ({"api":"32"}, {"api":"34"}, {"api":"unknown"}, {"arch":"x64"}, {"bootmode":"false"}, {"corrupt":True}):
            with self.subTest(args=args):
                self.assertEqual(self.install(**args).returncode, 2)

if __name__ == "__main__": unittest.main()
