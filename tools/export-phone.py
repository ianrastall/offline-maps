"""Export one installed region for Android's Import map folder action.

python -I tools/export-phone.py G:\\Maps us-il D:\\Transfers\\Illinois
Copy the resulting folder to the phone over USB, then select it in Offline Maps.
Includes only active map/search/layer files; never changes the installed maps.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import uuid


def export(source: Path, region: str, output: Path) -> None:
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", region):
        raise ValueError("Invalid region ID")
    source, output = source.resolve(), output.resolve()
    if source == output or source in output.parents or output in source.parents:
        raise ValueError("Choose an output folder outside the map store")
    if output.exists():
        raise FileExistsError(f"The output folder already exists: {output}")
    files: list[Path] = []
    for layer, data in [("", "package.mbtiles"), ("extras", "extras.sqlite"),
                        ("terrain", "terrain.mbtiles"), ("overlays", "overlays.mbtiles")]:
        base = Path(region) / layer
        pointer = source / base / "active.json"
        if layer and not pointer.exists():
            continue
        record = json.loads(pointer.read_text(encoding="utf-8"))
        version = record["version"]
        if record["schema"] != 1 or not re.fullmatch(r"[a-zA-Z0-9_-]+", version):
            raise ValueError(f"Invalid active pointer: {pointer}")
        directory = base / "versions" / version
        files.extend([base / "active.json", directory / "record.json", directory / data])
        if not layer:
            files.append(directory / "search.sqlite")
    size = sum((source / name).stat().st_size for name in files)
    output.parent.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(output.parent).free < size + (64 << 20):
        raise OSError("Not enough free disk space for this export")
    staged = output.with_name(output.name + ".part-" + uuid.uuid4().hex)
    try:
        staged.mkdir()
        manifest: dict = {"schema": 1, "region": region, "files": {}}
        for name in files:
            original = source / name
            if original.is_symlink() or any(p.is_symlink() for p in original.parents):
                raise ValueError("Map transfers cannot contain symbolic links")
            destination = staged / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            digest = hashlib.sha256()
            length = 0
            with original.open("rb") as reader, destination.open("xb") as writer:
                while chunk := reader.read(4 << 20):
                    digest.update(chunk)
                    writer.write(chunk)
                    length += len(chunk)
                writer.flush()
                os.fsync(writer.fileno())
            manifest["files"][name.as_posix()] = {"bytes": length, "sha256": digest.hexdigest()}
            print(f"{name.as_posix()} ({length / 1e6:.1f} MB)", flush=True)
        (staged / "transfer.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        staged.rename(output)
    except BaseException:
        # This unique staging directory was created above, beside the named output.
        shutil.rmtree(staged, ignore_errors=True)
        raise
    print(f"Ready: {output} ({size / 1e6:.1f} MB). Copy this whole folder to the phone.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("store", type=Path)
    parser.add_argument("region")
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    export(args.store, args.region, args.output)
