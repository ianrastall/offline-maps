"""Package Android notices, including the complete licenses of its Rust dependencies."""
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "android/app/src/main/assets/notices"


def cargo(*args):
    return subprocess.run(["cargo", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def main():
    rule = "\n\n" + "=" * 78 + "\n\n"
    # Shared renderer, fonts, sprites and terrain notices, without desktop-only components.
    text = (ROOT / "assets/notices/THIRD-PARTY-NOTICES.txt").read_text(encoding="utf-8")
    sections = [s for s in text.split(rule) if not s.startswith(("Rust crates", ".NET packages"))]
    tree = cargo("tree", "--locked", "-p", "offline-maps-android", "--target", "aarch64-linux-android", "-e", "normal", "--prefix", "none", "-f", "{p}")
    names = set(re.findall(r"^([\w-]+) v([\d.]+)", tree, flags=re.M))
    metadata = json.loads(cargo("metadata", "--locked", "--format-version", "1", "--filter-platform", "aarch64-linux-android"))
    for package in sorted(metadata["packages"], key=lambda p: p["name"]):
        if (package["name"], package["version"]) not in names or package["name"].startswith("offline-maps"):
            continue
        folder = Path(package["manifest_path"]).parent
        licenses = sorted({p for pattern in ("LICENSE*", "COPYING*", "NOTICE*") for p in folder.glob(pattern) if p.is_file()})
        # ring keeps third-party notices in its LICENSE file; crate license files
        # are included verbatim rather than replacing them with SPDX identifiers.
        sections.append(f'{package["name"]} {package["version"]}: {package["license"]}\n\n' +
                        "\n\n".join(p.read_text(encoding="utf-8", errors="replace") for p in licenses))
    sections.append("Android interface dependencies\n\nAndroidX Activity, Compose, Lifecycle, WebKit and their AndroidX dependencies: Copyright The Android Open Source Project, Apache License 2.0. Kotlin standard library: Copyright JetBrains s.r.o. and Kotlin Programming Language contributors, Apache License 2.0. Apache License 2.0 is reproduced above with the Rust dependencies, and at https://www.apache.org/licenses/LICENSE-2.0.")
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "THIRD-PARTY-NOTICES.txt").write_text(rule.join(sections) + "\n", encoding="utf-8")
    print(f"Android notices: {len(names)} dependency entries")


if __name__ == "__main__":
    main()
