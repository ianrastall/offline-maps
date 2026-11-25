use std::collections::BTreeMap;

use super::*;
use crate::package::sha256_file;
use crate::store::tests::TempDir;
use crate::terrain::active_terrain;
use crate::transfer::FileRecord;
use crate::transfer::tests::prepared;

fn none() -> AtomicBool {
    AtomicBool::new(false)
}

/// Illinois terrain alone, as a separate layer folder.
fn terrain_only(root: &Path) {
    let region = crate::regions::find("us-il").unwrap();
    let dir = root.join("us-il/terrain/versions/t1");
    fs::create_dir_all(&dir).unwrap();
    let db = rusqlite::Connection::open(dir.join("terrain.mbtiles")).unwrap();
    db.execute_batch(
        "CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);
         INSERT INTO tiles VALUES (0, 0, 0, x'52494646');",
    )
    .unwrap();
    drop(db);
    let record = serde_json::json!({
        "schema": 1,
        "region_id": "us-il",
        "source": crate::package::SourceInfo::default(),
        "archive_length": null,
        "bounds": region.outer,
        "min_zoom": 0,
        "max_zoom": 12,
        "tiles": 1,
        "bytes": 4,
        "downloaded_at": "today",
        "attribution": "Mapterhorn",
    });
    fs::write(dir.join("record.json"), record.to_string()).unwrap();
    fs::write(
        root.join("us-il/terrain/active.json"),
        r#"{"schema":1,"version":"t1"}"#,
    )
    .unwrap();
    let mut files = BTreeMap::new();
    for name in [
        "us-il/terrain/active.json",
        "us-il/terrain/versions/t1/record.json",
        "us-il/terrain/versions/t1/terrain.mbtiles",
    ] {
        let (sha256, bytes) = sha256_file(&root.join(name), &none()).unwrap();
        files.insert(name.to_owned(), FileRecord { bytes, sha256 });
    }
    let manifest = Transfer {
        schema: 1,
        region: "us-il".into(),
        files,
    };
    fs::write(root.join(MANIFEST), serde_json::to_vec(&manifest).unwrap()).unwrap();
}

#[test]
fn imports_regions_before_their_separate_terrain_and_skips_what_is_installed() {
    let library = TempDir::new();
    // Named so the terrain folder sorts first; it is still imported second.
    fs::create_dir_all(library.0.join("z-us-il")).unwrap();
    prepared(&library.0.join("z-us-il"));
    terrain_only(&library.0.join("a-us-il-terrain"));
    let target = TempDir::new();
    let store = PackageStore::new(&target.0);

    let mut seen = Vec::new();
    let result = import_library(&store, &library.0, &none(), |done, total, id| {
        seen.push((done, total, id.to_owned()));
    })
    .unwrap();
    assert_eq!(result.added, ["us-il"], "{result:?}");
    assert_eq!(result.layers_added, ["us-il"], "{result:?}");
    assert!(
        result.failed.is_empty() && result.needs_map.is_empty(),
        "{result:?}"
    );
    assert_eq!(seen.first().unwrap(), &(0, 2, "us-il".to_owned()));
    assert!(store.active("us-il").unwrap().is_some());
    assert_eq!(
        active_terrain(&store, "us-il")
            .unwrap()
            .unwrap()
            .record
            .tiles,
        1
    );
    assert!(!target.0.join(".imports").exists());
    // The library is left as it was.
    assert!(
        library
            .0
            .join("a-us-il-terrain/us-il/terrain/active.json")
            .is_file()
    );

    let again = import_library(&store, &library.0, &none(), |_, _, _| {}).unwrap();
    assert_eq!(again.skipped, 2);
    assert!(again.added.is_empty() && again.layers_added.is_empty());
}

#[test]
fn separate_terrain_needs_its_map() {
    let library = TempDir::new();
    terrain_only(&library.0.join("us-il-terrain"));
    let target = TempDir::new();
    let store = PackageStore::new(&target.0);
    // The folder itself may be selected, too.
    let result = import_library(
        &store,
        &library.0.join("us-il-terrain"),
        &none(),
        |_, _, _| {},
    )
    .unwrap();
    assert_eq!(result.needs_map, ["us-il"]);
    assert!(!store.region_dir("us-il").exists());
}

#[test]
fn refuses_the_map_folder_and_folders_without_maps() {
    let target = TempDir::new();
    let store = PackageStore::new(&target.0);
    fs::create_dir_all(target.0.join("inside")).unwrap();
    assert!(import_library(&store, &target.0.join("inside"), &none(), |_, _, _| {}).is_err());
    let empty = TempDir::new();
    assert!(import_library(&store, &empty.0, &none(), |_, _, _| {}).is_err());
}
