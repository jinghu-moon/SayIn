"""Copy the approved B-field masters into Android and Windows build inputs."""
from pathlib import Path
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "assets/sayin-icons/B-field"
RES = ROOT / "android/app/src/main/res"
DESKTOP = ROOT / "desktop/assets"


def main():
    copied = {}
    inputs = [(p, RES / p.relative_to(SOURCE / "android/res"))
              for p in sorted((SOURCE / "android/res").rglob("*")) if p.is_file()]
    inputs += [(SOURCE / "windows" / name, DESKTOP / target) for name, target in [
        ("sayin.ico", "sayin.ico"),
        ("tray/tray-for-dark-taskbar.ico", "sayin-tray-dark.ico"),
        ("tray/tray-for-light-taskbar.ico", "sayin-tray-light.ico"),
        ("png/sayin-64.png", "sayin-64.png"),
    ]]
    inputs.append((SOURCE / "windows/png/sayin-256.png", RES / "drawable-nodpi/sayin_brand.png"))
    for source, destination in inputs:
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        copied[destination.relative_to(ROOT).as_posix()] = {
            "source": source.relative_to(ROOT).as_posix(),
            "sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
        }
    # Remove only the former default-density adaptive icon; the approved assets
    # provide v26 adaptive icons and all five density-specific legacy fallbacks.
    (RES / "mipmap-anydpi/ic_launcher.xml").unlink(missing_ok=True)
    DESKTOP.mkdir(parents=True, exist_ok=True)
    (DESKTOP / "brand-assets.json").write_text(json.dumps({
        "name": "SayIn", "variant": "B-field", "files": copied,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"SayIn B-field: {len(copied)} original assets copied without modification.")


if __name__ == "__main__":
    main()
