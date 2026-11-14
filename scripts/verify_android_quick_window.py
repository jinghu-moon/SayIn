"""Check the real QuickActivity window with the phone unlocked and recording idle.

Uses an existing device root authorization only to open the non-exported Activity.
Never enters text, changes app settings, starts recording, or calls a cloud API.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def bounds(node):
    return tuple(map(int, re.findall(r"-?\d+", node.attrib["bounds"])))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", required=True)
    parser.add_argument("--use-existing-root", action="store_true", required=True)
    parser.add_argument("--adb", default=str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"))
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "docs/research")
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)

    def adb(*command):
        return subprocess.run([args.adb, "-s", args.device, *command], capture_output=True,
                              check=True, timeout=30).stdout

    assert b"uid=0(root)" in adb("shell", "su -c id"), "Existing device root authorization is required"
    services = adb("shell", "dumpsys", "activity", "services", "seeyuer.sayin").decode(errors="replace")
    assert "seeyuer.sayin/.quick.RecordingService" not in services, "Finish recording before this check"
    density_text = adb("shell", "wm", "density").decode()
    density = int(re.findall(r"(?:Physical|Override) density: (\d+)", density_text)[-1]) / 160
    minimum_target = round(48 * density)
    remote = "/data/local/tmp/sayin-quick-verification.xml"

    def tree():
        adb("shell", "uiautomator", "dump", remote)
        return ET.fromstring(adb("exec-out", "cat", remote))

    def screenshot(name):
        (output / name).write_bytes(adb("exec-out", "screencap", "-p"))

    def check_window():
        text = adb("shell", "dumpsys", "window", "windows").decode(errors="replace")
        start = text.index("seeyuer.sayin/seeyuer.sayin.quick.QuickActivity}:")
        section = text[start:start + 3500]
        frame = re.search(r"Frames: .*? frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]", section)
        assert frame, "Quick window frame unavailable"
        return tuple(map(int, frame.groups()))

    def settled_window(before_bottom=None):
        deadline = time.monotonic() + 15
        last = None
        while time.monotonic() < deadline:
            try:
                snapshot = tree()
                frame = check_window()
                root = bounds(next(n for n in snapshot.iter("node")))
                last = (root, frame)
                if (frame[0] <= root[0] < root[2] <= frame[2] and frame[1] <= root[1] < root[3] <= frame[3]
                        and (before_bottom is None or frame[3] < before_bottom)):
                    return snapshot, frame
            except ET.ParseError:
                pass  # uiautomator may report a transient non-XML startup error
            time.sleep(0.2)
        raise AssertionError(f"Decor does not fit the native window frame after settling: {last}")

    adb("shell", "am", "start", "-W", "-n", "seeyuer.sayin/.MainActivity")
    adb("shell", "su -c 'am start -W -n seeyuer.sayin/.quick.QuickActivity'")
    try:
        initial_tree, initial_frame = settled_window()
        initial_root = bounds(next(n for n in initial_tree.iter("node")))
        assert initial_root[3] <= initial_frame[3], "Decor must fit the native window frame"
        screenshot("37-production-quick.png")
        editor = next(n for n in initial_tree.iter("node") if n.attrib.get("class") == "android.widget.EditText")
        l, t, r, b = bounds(editor)
        assert b > t and r > l
        adb("shell", "input", "tap", str((l + r) // 2), str((t + b) // 2))
        editing_tree, frame = settled_window(initial_frame[3])
        root = bounds(next(n for n in editing_tree.iter("node")))
        assert root[3] <= frame[3], "The decor extends past the IME-clamped native frame"
        assert frame[3] < initial_frame[3], "The keyboard must be shown for this check"
        parents = {child: parent for parent in editing_tree.iter() for child in parent}
        send = next(n for n in editing_tree.iter("node")
                    if n.attrib.get("content-desc") in ("发送到电脑", "Send to computer"))
        button = send
        while button.attrib.get("clickable") != "true":
            button = parents[button]
        bl, bt, br, bb = bounds(button)
        assert br - bl >= minimum_target and bb - bt >= minimum_target, "Full 48dp send target must remain visible"
        assert frame[0] <= bl < br <= frame[2] and frame[1] <= bt < bb <= frame[3], "Send target is clipped by the keyboard"
        editor = next(n for n in editing_tree.iter("node") if n.attrib.get("class") == "android.widget.EditText")
        assert bounds(editor)[3] - bounds(editor)[1] >= round(28 * density), "Editor must fit a full line"
        screenshot("37-production-quick-keyboard.png")
        result = {"device": args.device, "initialFrame": initial_frame, "imeFrame": frame,
                  "decorFrame": root, "sendButton": (bl, bt, br, bb), "minimumTargetPx": minimum_target,
                  "textChanged": False, "recordingStarted": False, "cloudCalled": False}
        (output / "37-quick-window-smoke.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print("Native QuickActivity: decor fits the IME frame; full editor line and 48dp send target are visible.")
    finally:
        adb("shell", "input", "keyevent", "4")
        adb("shell", "am", "start", "-W", "-n", "seeyuer.sayin/.MainActivity")
        adb("shell", "rm", "-f", remote)


if __name__ == "__main__":
    main()
