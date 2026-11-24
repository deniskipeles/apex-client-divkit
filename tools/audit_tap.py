#!/usr/bin/env python3
"""Tap/dump helpers driven by the live uiautomator XML tree.

Usage:
  audit_tap.py find <text>            # print bounds center of first node whose text/desc contains <text>
  audit_tap.py tap <text>             # tap it
  audit_tap.py tapx <x> <y>           # tap absolute coords
  audit_tap.py nodes                  # list visible text nodes with bounds
"""
import re
import subprocess
import time
import sys
import xml.etree.ElementTree as ET

ADB = "adb"


def adb(*args, **kw):
    return subprocess.run([ADB, *args], capture_output=True, text=True, **kw).stdout


def dump(attempts: int = 4):
    """uiautomator refuses to dump mid-animation, so retry before giving up."""
    out = ""
    for _ in range(attempts):
        adb("shell", "rm", "-f", "/sdcard/ui.xml")
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        out = adb("shell", "cat", "/sdcard/ui.xml")
        if out.strip().startswith("<?xml"):
            return out
        time.sleep(1.0)
    return out


def nodes():
    xml = dump()
    xml = xml[xml.find("<?xml"):] if "<?xml" in xml else xml
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as exc:
        print("parse error", exc, file=sys.stderr)
        return []
    out = []
    for n in root.iter("node"):
        text = n.get("text") or ""
        desc = n.get("content-desc") or ""
        bounds = n.get("bounds") or ""
        if not (text or desc):
            continue
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({
            "text": text, "desc": desc,
            "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
            "bounds": (x1, y1, x2, y2), "clickable": n.get("clickable") == "true",
        })
    return out


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "nodes"
    if cmd == "nodes":
        for n in nodes():
            print(f"{n['bounds']} clickable={n['clickable']} text={n['text']!r} desc={n['desc']!r}")
        return
    if cmd == "tapx":
        adb("shell", "input", "tap", sys.argv[2], sys.argv[3])
        print("tapped", sys.argv[2], sys.argv[3])
        return
    needle = sys.argv[2].lower()
    all_nodes = nodes()

    def score(n):
        text = (n["text"] or n["desc"]).lower()
        exact = 0 if text == needle else 1
        # Prefer the deepest, clickable node that actually carries the label.
        return (exact, 0 if n["clickable"] else 1, len(text))

    matches = [n for n in all_nodes if needle in n["text"].lower() or needle in n["desc"].lower()]
    if not matches:
        print("NOT FOUND:", needle, file=sys.stderr)
        sys.exit(2)
    target = sorted(matches, key=score)[0]
    if cmd == "find":
        print(target)
        return
    adb("shell", "input", "tap", str(target["cx"]), str(target["cy"]))
    print(f"tapped {target['text'] or target['desc']!r} at {target['cx']},{target['cy']}")


if __name__ == "__main__":
    main()
