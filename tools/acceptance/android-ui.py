"""Small ADB UI inspection helper for the Android acceptance checks.

python -I tools/acceptance/android-ui.py SERIAL [--tap TEXT] [--shot FILE]
Prints visible text and bounds; --tap locates one exact text/accessibility label.
"""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("--tap")
    parser.add_argument("--shot", type=Path)
    args = parser.parse_args()

    def adb(*cmd, binary=False):
        return subprocess.run(["adb", "-s", args.serial, *cmd], check=True,
                              capture_output=True, text=not binary).stdout

    root = None
    for _ in range(3):
        message = adb("shell", "uiautomator", "dump", "/sdcard/offline-maps-ui.xml")
        if "dumped to" in message:
            root = ET.fromstring(adb("shell", "cat", "/sdcard/offline-maps-ui.xml"))
            break
        time.sleep(0.3)
    if root is None:
        raise RuntimeError("Android did not return an accessibility tree")
    nodes = list(root.iter("node"))
    if args.tap:
        matches = [n for n in nodes if args.tap in (n.get("text"), n.get("content-desc"))]
        matches = list({n.get("bounds"): n for n in matches}.values())
        if len(matches) != 1:
            raise RuntimeError(f"Expected one visible {args.tap!r}, found {len(matches)}")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", matches[0].get("bounds")))
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
        print(f"Tapped {args.tap}")
    else:
        for n in nodes:
            label = n.get("text") or n.get("content-desc")
            if label:
                print(f'{n.get("bounds")} {label[:500]}')
    if args.shot:
        args.shot.parent.mkdir(parents=True, exist_ok=True)
        args.shot.write_bytes(adb("exec-out", "screencap", "-p", binary=True))


if __name__ == "__main__":
    main()
