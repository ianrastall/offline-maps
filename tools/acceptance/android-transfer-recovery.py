"""Simulate interruption in a debug emulator's map store and verify real app startup recovery.

python -I tools/acceptance/android-transfer-recovery.py emulator-5554 us-de
Requires an installed map and terrain. Refuses physical-device serials.
"""
import argparse
import json
import re
import subprocess
import time
import uuid

PACKAGE = "io.github.ianrastall.offlinemaps"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("region")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial) or not re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", args.region):
        raise ValueError("Only a debug emulator and a catalog-style region ID are accepted")

    def adb(*command, content=None, check=True):
        return subprocess.run(["adb", "-s", args.serial, *command], input=content, capture_output=True, check=check)

    def read(path):
        return adb("exec-out", "run-as", PACKAGE, "cat", path).stdout

    def write(path, content):
        adb("shell", "run-as", PACKAGE, "tee", path, content=content)

    def exists(path):
        return adb("shell", "run-as", PACKAGE, "ls", "-d", path, check=False).returncode == 0

    root = "no_backup/maps"
    locations = [("map", args.region), ("terrain", f"{args.region}/terrain")]
    before = {layer: read(f"{root}/{region}/active.json") for layer, region in locations}
    saved = read(f"{root}/saved-places.json")
    versions = {layer: json.loads(value)["version"] for layer, value in before.items()}
    if any(not re.fullmatch(r"[A-Za-z0-9-]{1,64}", version) for version in versions.values()):
        raise ValueError("Unexpected version name")
    version = "import-recovery-" + uuid.uuid4().hex[:12]
    adb("shell", "am", "force-stop", PACKAGE)
    try:
        for layer, region in locations:
            adb("shell", "run-as", PACKAGE, "cp", "-R", f"{root}/{region}/versions/{versions[layer]}", f"{root}/{region}/versions/{version}")
        journal = {
            "schema": 1, "region": args.region, "committed": False,
            "changes": [{"layer": layer, "before": list(before[layer]), "version": version} for layer, _ in locations],
        }
        write(f"{root}/.transfer-update.reserve", bytes(65536))
        write(f"{root}/.transfer-update.json", json.dumps(journal).encode())
        # One pointer changed, the other still old: the process died mid-commit.
        write(f"{root}/{args.region}/active.json", json.dumps({"schema": 1, "version": version}, separators=(",", ":")).encode())
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity")
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            if all(read(f"{root}/{region}/active.json") == before[layer] for layer, region in locations) and not exists(f"{root}/.transfer-update.json"):
                break
            time.sleep(0.25)
        else:
            raise RuntimeError("Startup did not restore the previous pointer set")
        if read(f"{root}/saved-places.json") != saved:
            raise RuntimeError("Recovery changed saved places")
        if exists(f"{root}/.transfer-update.reserve") or any(exists(f"{root}/{region}/versions/{version}") for _, region in locations):
            raise RuntimeError("Recovery left its reserve or uncommitted versions behind")
        print("App startup restored both old pointers, preserved saved places and removed the journal/reserve/uncommitted versions.")
    finally:
        # Leave the app available even if an assertion failed; a valid journal
        # remains available for its next startup to retry recovery.
        adb("shell", "am", "start", "-n", PACKAGE + "/.MainActivity", check=False)


if __name__ == "__main__":
    main()
