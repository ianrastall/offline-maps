"""Publishes the prepared stations-and-addresses files made by `offline-maps-dev prepare-extras`
in the `extras` release, and writes crates/offline-maps-core/src/prepared_data.rs (their sizes,
which the app shows before a download).

Usage: python -I tools/bundles/publish.py <folder> [--no-upload]
       then cargo fmt

Checks every file the folder's manifest.json lists (size and SHA-256) before anything is
uploaded. Uploads the files that the release lacks or holds with another size, then
manifest.json last, so a copy of the app never finds a manifest listing a file that is not
there yet. A file replaced in place is briefly missing or newer than the manifest says; the app
then reads that region's raw extract instead. Files the manifest no longer lists are left in
the release. Needs the GitHub CLI (`gh`), signed in with rights to the repository.
"""
from __future__ import annotations

import hashlib
import json
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO = "ianrastall/offline-maps"
TAG = "extras"
TITLE = "Stations and addresses"
ROOT = Path(__file__).resolve().parents[2]
SIZES = ROOT / "crates" / "offline-maps-core" / "src" / "prepared_data.rs"
NOTES = """Prepared stations and addresses for each region, downloaded by Offline Maps from Map > Regions instead of each copy reading Geofabrik's raw OpenStreetMap extract. One SQLite file per region, `<region>-extras.sqlite`: fuel and charging stations, street addresses, shelters, drinking water, campgrounds and toilets, as the app itself reads them from the extract, with the extract's data date recorded inside. `manifest.json` lists each file's size, SHA-256 and data date; the app checks a download against it and against the file's own contents. Regions not listed here are read from the raw extract by the app as before.

Made by `offline-maps-dev prepare-extras` and published by `tools/bundles/publish.py`. Files are replaced in place when they are rebuilt.

Data © OpenStreetMap contributors, from Geofabrik's extracts (download.geofabrik.de), available under the Open Database License 1.0 (https://opendatacommons.org/licenses/odbl/1-0/). These files are derived databases offered under the same licence."""


def gh(*args: str, capture: bool = False) -> str:
    result = subprocess.run(["gh", *args], check=True, capture_output=capture, text=True)
    return result.stdout if capture else ""


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def write_sizes(manifest: dict) -> None:
    rows = sorted((rid, e["bytes"]) for rid, e in manifest["extras"].items())
    generated = manifest["generated_at"][:10]
    lines = [
        "//! Sizes of the prepared stations-and-addresses files published in the `extras`",
        f"//! release, by region, from its manifest of {generated}. Written by",
        "//! tools/bundles/publish.py; do not edit. Sorted by region ID.",
        "",
        "/// (region ID, bytes)",
        "pub const PREPARED_EXTRAS: &[(&str, u64)] = &[",
        *(f'    ("{rid}", {size}),' for rid, size in rows),
        "];",
        "",
    ]
    SIZES.write_text("\n".join(lines), encoding="utf-8", newline="\n")
    print(f"{SIZES.relative_to(ROOT)}: {len(rows)} regions")


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    upload = "--no-upload" not in sys.argv
    if len(args) != 1:
        sys.exit(__doc__)
    folder = Path(args[0])
    manifest_path = folder / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    entries = manifest["extras"]
    for rid, entry in sorted(entries.items()):
        path = folder / entry["file"]
        if path.stat().st_size != entry["bytes"] or sha256(path) != entry["sha256"]:
            sys.exit(f"{entry['file']} is not the file the manifest lists for {rid}")
    print(f"{len(entries)} files match the manifest of {manifest['generated_at']}")
    if upload:
        try:
            listed = json.loads(gh("release", "view", TAG, "--repo", REPO, "--json", "assets", capture=True))
            published = {a["name"]: a["size"] for a in listed["assets"]}
        except subprocess.CalledProcessError:
            gh("release", "create", TAG, "--repo", REPO, "--title", TITLE, "--notes", NOTES, "--latest=false")
            published = {}
        todo = [e for e in entries.values() if published.get(e["file"]) != e["bytes"]]
        print(f"{len(todo)} to upload, {len(entries) - len(todo)} already published")
        for i, entry in enumerate(sorted(todo, key=lambda e: e["file"]), 1):
            print(f"[{i}/{len(todo)}] {entry['file']} ({entry['bytes'] / 1e6:.1f} MB)", flush=True)
            gh("release", "upload", TAG, str(folder / entry["file"]), "--repo", REPO, "--clobber")
        gh("release", "upload", TAG, str(manifest_path), "--repo", REPO, "--clobber")
        gh("release", "edit", TAG, "--repo", REPO, "--notes", NOTES)
        print(f"manifest.json published at {datetime.now(timezone.utc):%Y-%m-%d %H:%M} UTC")
    write_sizes(manifest)


if __name__ == "__main__":
    main()
