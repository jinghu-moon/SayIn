"""Explicit opt-in device verification; synthetic prefix and private isolated data only."""
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
    parser.add_argument("--adb", default="C:/Users/seeyuer/AppData/Local/Android/Sdk/platform-tools/adb.exe")
    args = parser.parse_args()
    if not args.allow_synthetic_clipboard_writes:
        parser.error("Explicit --allow-synthetic-clipboard-writes required; clipboard will be replaced with generated samples")
    adb = [args.adb, "-s", args.serial]
    stamp = str(time.time_ns())
    base = f"/data/local/tmp/sayin-native-verification-{stamp}"
    binary = base + "-bin"
    prefix = "SAYIN_TEST_" + stamp + "_"
    package = ROOT / "root-helper/build/package/sayin-helper"
    evidence = {"binary_sha256": hashlib.sha256(package.read_bytes()).hexdigest(), "cases": {}}

    def call(arguments, check=True):
        result = subprocess.run(adb + arguments, capture_output=True, text=True, encoding="utf-8", timeout=30)
        if check and result.returncode:
            raise RuntimeError(f"Device command failed ({result.returncode}): {result.stderr or result.stdout}")
        return result

    def root(command, check=True):
        return call(["shell", "su", "-c", shlex.quote(command)], check)

    def helper(command, data=base, check=True):
        return root(f"{binary} {command} --data-dir {data}", check)

    def status():
        return json.loads(helper("status").stdout)

    def fixture(tag, count=3, delay=200, mode="normal"):
        result = call(["shell", "am", "instrument", "-w", "-e", "prefix", prefix+tag+"_", "-e", "count", str(count), "-e", "delay", str(delay), "-e", "mode", mode,
                       "seeyuer.sayin.helperfixture/.ClipboardFixture"])
        assert f"samples_written={count}" in result.stdout and "INSTRUMENTATION_CODE: 0" in result.stdout

    def wait_for(predicate, seconds=5):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            value = status()
            if predicate(value): return value
            time.sleep(.1)
        raise AssertionError("Device state timeout")

    process = None
    try:
        assert root("getprop ro.build.version.sdk").stdout.strip() == "33"
        call(["push", str(package), binary])
        root(f"chmod 755 {binary}")
        assert "true" in root(f"{binary} crypto-test").stdout
        assert "true" in root(f"{binary} probe").stdout
        evidence["cases"]["binder_and_crypto"] = True
        helper("enable")

        def start(module=""):
            suffix = f" --module-dir {module}" if module else ""
            return subprocess.Popen(adb + ["shell", "su", "-c", shlex.quote(f"{binary} run --data-dir {base} --test-prefix {prefix} --duration 120{suffix}")], stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        process = start()
        wait_for(lambda s: s.get("capture_ready"))
        fixture("normal", 5)
        assert wait_for(lambda s: s.get("accepted", 0) == 5)["entries"] == 5
        evidence["cases"]["five_continuous_utf8"] = True
        for mode in ("sensitive", "oversize", "uri"):
            fixture(mode, 1, mode=mode)
        assert status()["accepted"] == 5
        evidence["cases"]["sensitive_oversize_uri_skipped"] = True
        duplicate = helper("run --duration 1", check=False)
        assert duplicate.returncode != 0 and "instance_busy" in duplicate.stderr
        evidence["cases"]["duplicate_instance_rejected"] = True
        helper("clear")
        assert status()["entries"] == 0
        fixture("after_clear", 2)
        assert wait_for(lambda s: s.get("entries") == 2)["accepted"] == 7
        helper("disable"); process.communicate(timeout=10); assert process.returncode == 0
        saved = json.loads(helper("list").stdout)
        assert len(saved["entries"]) == 2 and all(prefix+"after_clear_" in e["text"] for e in saved["entries"])
        evidence["cases"]["clear_barrier_and_persistence"] = True
        encrypted = subprocess.run(adb+["shell", "-T", "su", "-c", shlex.quote(f"cat {base}/history.enc")], capture_output=True, timeout=10)
        assert encrypted.returncode == 0 and prefix.encode() not in encrypted.stdout and encrypted.stdout.startswith(b"SIE1")
        evidence["cases"]["encrypted_at_rest"] = True
        denied = call(["shell", binary, "list", "--data-dir", base], check=False)
        assert denied.returncode != 0 and "root_required" in denied.stderr
        evidence["cases"]["unprivileged_history_denied"] = True

        helper("enable"); process = start(); wait_for(lambda s: s.get("capture_ready"))
        assert status()["entries"] == 2 and status()["accepted"] == 0
        helper("stop"); process.communicate(timeout=10)
        assert not status()["running"] and status()["enabled"]
        evidence["cases"]["restart_preserves_history_no_reimport_stop_preserves_optin"] = True

        # Kill only the verified isolated parent and confirm its shell child cannot linger.
        pidfile = base + "-parent.pid"
        launched = f"{binary} run --data-dir {base} --test-prefix {prefix} --duration 120 & parent=$!; echo $parent > {pidfile}; wait $parent"
        process = subprocess.Popen(adb + ["shell", "su", "-c", shlex.quote(launched)], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        wait_for(lambda s: s.get("capture_ready"))
        pid = root(f"cat {pidfile}").stdout.strip()
        assert pid.isdigit() and int(pid) > 1
        assert root(f"readlink /proc/{pid}/exe").stdout.strip() == binary
        rows = root("ps -A -o PID,PPID").stdout.splitlines()
        children = [row.split()[0] for row in rows if len(row.split()) == 2 and row.split()[1] == pid]
        assert len(children) == 1 and children[0].isdigit()
        root(f"kill -9 {pid}")
        process.communicate(timeout=10)
        # A child may briefly be a zombie while init reaps it, but must not execute anymore.
        child_state = root(f"cat /proc/{children[0]}/stat", check=False)
        assert child_state.returncode != 0 or child_state.stdout.split(')')[-1].strip().startswith('Z')
        assert not status()["running"]
        root(f"rm {pidfile}")
        evidence["cases"]["parent_death_stops_shell_child"] = True

        module = base+"-module"
        root(f"mkdir -m 700 {module}")
        process = start(module); wait_for(lambda s: s.get("capture_ready"))
        root(f"touch {module}/disable")
        process.communicate(timeout=10)
        assert process.returncode == 0 and not status()["running"]
        root(f"rm {module}/disable; rmdir {module}")
        evidence["cases"]["module_disable_stops_both_processes"] = True

        corrupt = base+"-corrupt"
        root(f"mkdir -m 700 {corrupt}; cp {base}/key {corrupt}/key; cp {base}/history.enc {corrupt}/history.enc; chmod 600 {corrupt}/key {corrupt}/history.enc; printf X | dd of={corrupt}/history.enc bs=1 seek=40 conv=notrunc 2>/dev/null")
        result = helper("list", corrupt, check=False)
        assert result.returncode != 0 and "authentication_failed" in result.stderr
        assert helper("enable", corrupt, check=False).returncode != 0
        evidence["cases"]["tampered_store_blocks_read_and_enable"] = True
        # A missing key must not silently create a new identity over existing history.
        root(f"rm {corrupt}/key")
        result = helper("enable", corrupt, check=False)
        assert result.returncode != 0 and "storage_incomplete" in result.stderr
        evidence["cases"]["missing_key_not_recreated"] = True
        for directory in (corrupt, base):
            helper("disable", directory); helper("purge", directory)
        evidence["cases"]["owned_data_purge"] = True
        root(f"rm {binary}; rm {base}/instance.lock {corrupt}/instance.lock; rmdir {base} {corrupt}")
    finally:
        if process and process.poll() is None:
            helper("disable", check=False)
            try: process.communicate(timeout=10)
            except subprocess.TimeoutExpired: process.terminate()
        target = ROOT / "docs/research/native-clipboard/evidence/device-final.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(evidence, indent=2)+"\n", encoding="utf-8")
        print(json.dumps(evidence, indent=2))

if __name__ == "__main__": main()
