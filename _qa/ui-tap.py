# -*- coding: utf-8 -*-
"""Хелпер сверки: поиск node в uiautomator-dump по тексту и тап по центру.

  python _qa/ui-tap.py find "<текст>"          — координаты центра node
  python _qa/ui-tap.py tap "<текст>"           — тап по центру первой node с текстом
  python _qa/ui-tap.py tapdesc "<desc>"        — тап по content-desc
"""
import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = None


def adb(*args):
    r = subprocess.run([ADB, *args], capture_output=True, timeout=60)
    if r.returncode != 0:
        raise SystemExit(f"adb {' '.join(args)} -> {r.returncode}: {r.stderr.decode(errors='replace')[:300]}")
    return r.stdout


def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    data = adb("shell", "cat", "/sdcard/ui.xml")
    return ET.fromstring(data.decode("utf-8"))


def nodes(root, attr, needle):
    out = []
    for n in root.iter("node"):
        v = n.attrib.get(attr) or ""
        if needle.lower() in v.lower() and n.attrib.get("clickable") == "true":
            out.append(n)
    return out


def center(n):
    l, t, r, b = (int(x) for x in n.attrib["bounds"].replace("][", ",").strip("[]").split(","))
    return (l + r) // 2, (t + b) // 2, (l, t, r, b)


def main():
    global ADB
    import os
    ADB = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk", "platform-tools", "adb.exe")
    mode, needle = sys.argv[1], sys.argv[2]
    root = dump()
    attr = "content-desc" if mode == "tapdesc" else "text"
    found = nodes(root, attr, needle)
    if not found and mode.startswith("tap"):
        # точное совпадение всего текста (например, «Дальше» среди длинных строк)
        for n in root.iter("node"):
            v = n.attrib.get(attr) or ""
            if v.strip().lower() == needle.lower():
                found.append(n)
    if not found and mode == "find":
        for n in root.iter("node"):
            v = n.attrib.get(attr) or ""
            if needle.lower() in v.lower():
                found.append(n)
    if not found:
        print(f"NOT FOUND: {needle!r}")
        return 1
    n = found[0]
    x, y, b = center(n)
    print(f"{n.attrib.get('text','')!r} desc={n.attrib.get('content-desc','')!r} bounds={b}")
    if mode.startswith("tap"):
        adb("shell", "input", "tap", str(x), str(y))
        print(f"TAP {x} {y}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
