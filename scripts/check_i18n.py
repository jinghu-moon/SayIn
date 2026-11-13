"""Check app-owned translations, locale declarations and common hardcoded Compose text.

Android Lint remains authoritative for Android/Java formatter syntax. This checker
adds complete-key parity and simple UI literal checks; it is not a Kotlin parser.
No network or third-party Python packages are needed.
"""

from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "android/app/src/main/res"
UI = ROOT / "android/app/src/main/java/seeyuer/sayin/ui"
ANDROID = "{http://schemas.android.com/apk/res/android}"
PARAMETER = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([tT][a-zA-Z]|[a-zA-Z%])")


def read_messages(directory: Path) -> dict:
    messages = {}
    for file in sorted(directory.glob("*.xml")):
        for element in ET.parse(file).getroot():
            if element.tag not in {"string", "plurals", "string-array"}:
                continue
            name = element.attrib["name"]
            if name in messages:
                raise ValueError(f"Duplicate resource: {directory.name}/{name}")
            messages[name] = element
    return messages


def placeholders(element: ET.Element) -> Counter:
    text = "".join(element.itertext())
    result = Counter()
    for index, kind in PARAMETER.findall(text):
        if kind in {"%", "n"}:
            continue
        if not index:
            raise ValueError(f"Use numbered placeholders such as %1$s: {text}")
        result[(index, kind)] += 1
    return result


def compare_resource(name: str, base: ET.Element, translated: ET.Element) -> None:
    if base.tag != translated.tag:
        raise ValueError(f"Resource type mismatch: {name}")
    if base.get("formatted", "true") != translated.get("formatted", "true"):
        raise ValueError(f"Formatting policy mismatch: {name}")
    if not "".join(translated.itertext()).strip():
        raise ValueError(f"Empty translation: {name}")
    if base.tag == "string":
        pairs = [(base, translated)]
    elif base.tag == "plurals":
        left = {item.attrib["quantity"]: item for item in base}
        right = {item.attrib["quantity"]: item for item in translated}
        if "other" not in left or "other" not in right:
            raise ValueError(f"Missing plural 'other': {name}")
        # Languages have different plural categories. Do not require Chinese 'one'.
        pairs = [(left.get(quantity, left["other"]), item) for quantity, item in right.items()]
    else:
        if len(base) != len(translated):
            raise ValueError(f"Array length mismatch: {name}")
        pairs = list(zip(base, translated))
    if base.get("formatted", "true") != "false":
        for left, right in pairs:
            if placeholders(left) != placeholders(right):
                raise ValueError(f"Placeholder mismatch: {name}")


def validate_translations(res: Path) -> set[str]:
    locales = [e.attrib[ANDROID + "name"] for e in ET.parse(res / "xml/locale_config.xml").getroot()]
    if len(locales) != len(set(locales)) or "en" not in locales:
        raise ValueError("locale_config must have unique tags and the English fallback")
    default = read_messages(res / "values")
    required = {key: value for key, value in default.items() if value.get("translatable") != "false"}
    for tag in locales:
        if tag == "en":
            continue
        translated = read_messages(res / ("values-b+" + tag.replace("-", "+")))
        missing = set(required) - set(translated)
        extra = set(translated) - set(required)
        if missing or extra:
            raise ValueError(f"{tag}: missing={sorted(missing)}, extra/nontranslatable={sorted(extra)}")
        for name, element in translated.items():
            compare_resource(name, required[name], element)
    return set(locales)


def main() -> None:
    locales = validate_translations(RES)
    declarations = (UI / "localization/AppLanguage.kt").read_text(encoding="utf-8")
    declared = set(re.findall(r'^\s*\w+\("([^\"]*)",', declarations, re.MULTILINE)) - {""}
    if declared != locales:
        raise ValueError(f"Language picker and locale_config differ: {declared} != {locales}")
    patterns = [r'\b(?:Text|TextButton)\s*\(\s*(?:text\s*=\s*)?"[^"\s]',
                r'\b(?:label|title|summary|contentDescription)\s*=\s*"[^"\s]']
    for source in UI.rglob("*.kt"):
        text = source.read_text(encoding="utf-8")
        if any(re.search(pattern, text) for pattern in patterns):
            raise ValueError(f"Possible hardcoded UI text: {source.relative_to(ROOT)}")
    print(f"I18N checks passed: {', '.join(sorted(locales))}; complete keys and matching placeholders.")


if __name__ == "__main__":
    main()
