"""Interactive installed-ELF lock/unlock check; writes generated clipboard samples only."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--allow-synthetic-clipboard-writes", action="store_true")
    parser.add_argument("--temporary", action="store_true", help="Test a temporary ELF without replacing the installed module")
    parser.add_argument("--start-locked", action="store_true", help="Start with the device already locked")
    parser.add_argument("--adb", default="C:/Users/seeyuer/AppData/Local/Android/Sdk/platform-tools/adb.exe")
    args = parser.parse_args()
    if not args.allow_synthetic_clipboard_writes:
        parser.error("Generated samples replace the clipboard; explicit opt-in required")
    adb = [args.adb, "-s", args.serial]
    stamp = str(time.time_ns())
    base = "/data/local/tmp/sayin-lock-verification-" + stamp
    prefix = "SAYIN_TEST_" + stamp + "_"
    binary = base + "-bin" if args.temporary else "/data/adb/modules/sayin_helper/bin/sayin-helper"
    writer = base + ".apk"
    evidence = {"serial": args.serial, "source": "temporary_binary" if args.temporary else "installed_module", "cases": {}}

    def call(parts, check=True):
        result = subprocess.run(adb + parts, capture_output=True, text=True, encoding="utf-8", timeout=20)
        if check and result.returncode:
            raise RuntimeError(result.stderr or result.stdout)
        return result

    def root(command, check=True):
        return call(["shell", "su", "-c", shlex.quote(command)], check)

    def helper(command, check=True):
        return root(f"{binary} {command} --data-dir {base}", check)

    def status():
        return json.loads(helper("status").stdout)

    def wait_for(predicate, timeout, stable=0):
        until = time.monotonic() + timeout
        since = None
        while time.monotonic() < until:
            value = status()
            if predicate(value):
                if since is None: since = time.monotonic()
                if time.monotonic() - since >= stable: return value
            else:
                since = None
            time.sleep(.3)
        raise AssertionError("Lock test state timed out")

    def write(tag):
        result = call(["shell", f"CLASSPATH={writer} app_process /system/bin seeyuer.sayin.helperfixture.ShellClipboardWriter {prefix}{tag}_"])
        assert "samples_written=3" in result.stdout

    process = None
    initialized = False
    try:
        assert root("getprop ro.build.version.sdk").stdout.strip() == "37"
        expected = hashlib.sha256((ROOT / "root-helper/build/package/sayin-helper").read_bytes()).hexdigest()
        if args.temporary:
            call(["push", str(ROOT / "root-helper/build/package/sayin-helper"), binary])
            call(["shell", "chmod", "755", binary])
        assert root(f"sha256sum {binary}").stdout.split()[0] == expected
        evidence["binary_sha256"] = expected
        # Never initialize, enable or inspect production clipboard history.
        assert root("[ ! -e /data/adb/sayin-helper/enabled ] && echo inactive").stdout.strip() == "inactive"
        call(["push", str(ROOT / "root-helper/build/device-fixture/fixture.apk"), writer])
        helper("enable"); initialized = True
        command = f"{binary} run --data-dir {base} --test-prefix {prefix} --duration 600"
        process = subprocess.Popen(adb + ["shell", "su", "-c", shlex.quote(command)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        accepted_before = 0
        if not args.start_locked:
            wait_for(lambda s: s.get("capture_ready"), 10)
            write("before")
            wait_for(lambda s: s.get("accepted") == 3, 5)
            helper("clear")
            assert status()["entries"] == 0
            evidence["cases"]["unlocked_shell_writer_baseline"] = True
            accepted_before = 3
            print("WAITING_FOR_LOCK", flush=True)
        wait_for(lambda s: s.get("capture_ready") is False, 240, stable=2)
        write("locked")
        time.sleep(2)
        assert status()["entries"] == 0 and status()["accepted"] == accepted_before
        evidence["cases"]["locked_samples_not_stored"] = True
        print("LOCKED_PHASE_PASSED_WAITING_FOR_UNLOCK", flush=True)
        wait_for(lambda s: s.get("capture_ready") is True, 240)
        assert status()["entries"] == 0
        write("after")
        wait_for(lambda s: s.get("accepted") == accepted_before + 3, 5)
        helper("disable")
        process.communicate(timeout=10)
        assert process.returncode == 0
        saved = json.loads(helper("list").stdout)
        assert len(saved["entries"]) == 3
        assert all(e["text"].startswith(prefix + "after_") for e in saved["entries"])
        evidence["cases"]["unlock_resumes_without_locked_reimport"] = True
        helper("purge")
        root(f"rm {base}/instance.lock; rmdir {base}")
        initialized = False
        assert root("[ ! -e /data/adb/sayin-helper/enabled ] && echo inactive").stdout.strip() == "inactive"
        evidence["cases"]["production_collection_left_inactive"] = True
    finally:
        if initialized:
            helper("disable", check=False)
        if process and process.poll() is None:
            process.communicate(timeout=10)
        call(["shell", "rm", "-f", writer], check=False)
        if args.temporary: call(["shell", "rm", "-f", binary], check=False)
        suffix = "-temporary" if args.temporary else "-installed"
        path = ROOT / f"docs/research/native-clipboard/evidence/lock-api37-{args.serial}{suffix}.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(evidence, indent=2), flush=True)


if __name__ == "__main__":
    main()
