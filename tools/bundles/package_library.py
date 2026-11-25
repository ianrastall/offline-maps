"""Builds the map library ZIPs published with a release.

Usage: python -I tools/bundles/package_library.py <store-dir> <out-dir> <region-id>...

Each installed region becomes two archives of the format that Windows
(Map > Import map library) and Android (Maps > Restore map library) read once
extracted:

  OfflineMaps-Library-<id>.zip          map, search, stations and addresses,
                                        flood zones and public lands
  OfflineMaps-Library-<id>-terrain.zip  terrain alone, when it fits

  Offline Maps Library/<id>/transfer.json
  Offline Maps Library/<id>/<id>/...                 store layout
  Offline Maps Library/<id>-terrain/transfer.json
  Offline Maps Library/<id>-terrain/<id>/terrain/...

Every file's SHA-256 is computed from the bytes written to the archive, and the
map package must match its own record. Archives over GitHub's 2 GiB limit are
not kept. Existing archives in <out-dir> are left as they are, so a stopped run
continues. Run `offline-maps-dev verify-transfer` on extracted samples.
"""
import hashlib
import json
import os
import sys
import zipfile

LIMIT = 2 * 1024**3 - 16 * 1024**2  # GitHub's 2 GiB per file, with room to spare
ROOT = 'Offline Maps Library'
LAYERS = {'extras': 'extras.sqlite', 'overlays': 'overlays.mbtiles', 'terrain': 'terrain.mbtiles'}


def active(store, layer_dir):
    """Store-relative names of a layer's pointer and active version files."""
    pointer = os.path.join(store, layer_dir, 'active.json')
    if not os.path.isfile(pointer):
        return None
    with open(pointer, encoding='utf-8') as f:
        version = json.load(f)['version']
    if not version.replace('-', '').replace('_', '').isalnum():
        raise ValueError(f'{layer_dir}: unexpected version {version!r}')
    return f'{layer_dir}/active.json', f'{layer_dir}/versions/{version}'


def region_files(store, region):
    """(main files, terrain files) as store-relative names."""
    found = active(store, region)
    if found is None:
        raise ValueError(f'{region} has no installed map')
    pointer, version = found
    main = [pointer] + [f'{version}/{name}' for name in ('record.json', 'package.mbtiles', 'search.sqlite')]
    terrain = []
    for layer, file in LAYERS.items():
        found = active(store, f'{region}/{layer}')
        if found is None:
            continue
        pointer, version = found
        names = [pointer, f'{version}/record.json', f'{version}/{file}']
        (terrain if layer == 'terrain' else main).extend(names)
    for name in main + terrain:
        path = os.path.join(store, name)
        if not os.path.isfile(path) or os.path.islink(path) or os.path.getsize(path) == 0:
            raise ValueError(f'{name} is missing or empty')
    return main, terrain


def write_archive(store, out, region, folder, names):
    """Writes one archive; returns its path, or None when it is too large."""
    target = os.path.join(out, f'OfflineMaps-Library-{folder}.zip')
    if os.path.exists(target):
        print(f'{folder}: kept existing archive', flush=True)
        return target
    total = sum(os.path.getsize(os.path.join(store, n)) for n in names)
    compressible = sum(os.path.getsize(os.path.join(store, n)) for n in names if not n.endswith('.mbtiles'))
    if total - compressible > LIMIT:
        print(f'{folder}: {total / 1024**3:.2f} GiB, over the limit; skipped', flush=True)
        return None
    partial = target + '.part'
    files = {}
    with zipfile.ZipFile(partial, 'w', allowZip64=True) as archive:
        for name in names:
            path = os.path.join(store, name)
            # Tiles are already compressed; databases and records are not.
            method = zipfile.ZIP_STORED if name.endswith('.mbtiles') else zipfile.ZIP_DEFLATED
            info = zipfile.ZipInfo.from_file(path, f'{ROOT}/{folder}/{name}')
            info.compress_type = method
            digest = hashlib.sha256()
            size = 0
            with open(path, 'rb') as source, archive.open(info, 'w') as sink:
                while chunk := source.read(8 * 1024 * 1024):
                    digest.update(chunk)
                    size += len(chunk)
                    sink.write(chunk)
            if size != os.path.getsize(path):
                raise ValueError(f'{name} changed while it was archived')
            files[name] = {'bytes': size, 'sha256': digest.hexdigest()}
        if f'{region}/active.json' in files:
            record_name = next(n for n in names if n.endswith('/record.json') and n.count('/') == 3)
            with open(os.path.join(store, record_name), encoding='utf-8') as f:
                expected = json.load(f)['sha256']
            package = next(n for n in names if n.endswith('/package.mbtiles'))
            if files[package]['sha256'] != expected:
                raise ValueError(f'{region}: the map does not match its record')
        manifest = {'schema': 1, 'region': region, 'files': dict(sorted(files.items()))}
        archive.writestr(f'{ROOT}/{folder}/transfer.json', json.dumps(manifest, indent=2) + '\n',
                         compress_type=zipfile.ZIP_DEFLATED)
    size = os.path.getsize(partial)
    if size > LIMIT:
        os.remove(partial)
        print(f'{folder}: archive {size / 1024**3:.2f} GiB, over the limit; removed', flush=True)
        return None
    os.replace(partial, target)
    print(f'{folder}: {size / 1024**3:.3f} GiB', flush=True)
    return target


def sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as f:
        while chunk := f.read(8 * 1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    store, out, regions = sys.argv[1], sys.argv[2], sys.argv[3:]
    os.makedirs(out, exist_ok=True)
    if os.path.realpath(out).startswith(os.path.realpath(store) + os.sep):
        sys.exit('Choose an output folder outside the map store.')
    made, skipped = [], []
    for region in regions:
        main_files, terrain_files = region_files(store, region)
        for folder, names in [(region, main_files), (f'{region}-terrain', terrain_files)]:
            if not names:
                continue
            path = write_archive(store, out, region, folder, names)
            (made if path else skipped).append(path or folder)
    with open(os.path.join(out, 'OfflineMaps-Library-SHA256SUMS.txt'), 'w', encoding='utf-8', newline='\n') as f:
        for path in sorted(made):
            f.write(f'{sha256(path)}  {os.path.basename(path)}\n')
    print(f'{len(made)} archives; not packaged: {", ".join(skipped) or "none"}')


if __name__ == '__main__':
    main()
