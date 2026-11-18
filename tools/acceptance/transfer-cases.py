"""Create fresh public-map fixtures for Android transfer acceptance.

python -I tools/acceptance/transfer-cases.py PREPARED_EXPORT NEW_OUTPUT_FOLDER
Never changes the input. Produces valid, older-date, damaged and map-only cases.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    source, output = args.source.resolve(), args.output.resolve()
    if output.exists() or source == output or source in output.parents:
        raise ValueError("Choose a new output folder outside the prepared export")
    manifest = json.loads((source / "transfer.json").read_text(encoding="utf-8"))
    region = manifest["region"]
    pointer = json.loads((source / region / "active.json").read_text(encoding="utf-8"))
    version = pointer["version"]
    record_name = f"{region}/versions/{version}/record.json"
    package_name = f"{region}/versions/{version}/package.mbtiles"
    for case in ("verified", "older", "damaged", "map-only"):
        folder = output / case
        folder.mkdir(parents=True)
        files = {}
        for name, record in manifest["files"].items():
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts or path.parts[0] != region:
                raise ValueError("Unsafe fixture path")
            if case == "map-only" and name != f"{region}/active.json" and not name.startswith(f"{region}/versions/"):
                continue
            target = folder / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / name, target)
            files[name] = dict(record)
        if case == "older":
            path = folder / record_name
            record = json.loads(path.read_text(encoding="utf-8"))
            record["source"]["last_modified"] = "Sun, 01 Nov 2026 00:00:00 GMT"
            content = (json.dumps(record, indent=2) + "\n").encode("utf-8")
            path.write_bytes(content)
            files[record_name] = {"bytes": len(content), "sha256": hashlib.sha256(content).hexdigest()}
        if case == "damaged":
            with (folder / package_name).open("r+b") as file:
                file.seek(128)
                byte = file.read(1)
                file.seek(128)
                file.write(bytes([byte[0] ^ 255]))
            # Intentionally leave the manifest's original checksum unchanged.
        (folder / "transfer.json").write_text(json.dumps({"schema": 1, "region": region, "files": files}, indent=2) + "\n", encoding="utf-8")
        print(f"{case}: {len(files)} files")


if __name__ == "__main__":
    main()
