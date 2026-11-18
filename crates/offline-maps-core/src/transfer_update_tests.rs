use super::*;
use crate::store::tests::TempDir;
use crate::transfer::tests::prepared_named;

fn none() -> AtomicBool {
    AtomicBool::new(false)
}

#[test]
fn opaque_damaged_pointer_bytes_can_be_repaired_and_restored_exactly() {
    let target = TempDir::new();
    let store = PackageStore::new(&target.0);
    fs::create_dir_all(store.region_dir("us-il")).unwrap();
    let pointer = pointer_path(&store, "us-il", Layer::Map);
    let original = vec![255, 0, 254, 13, 10];
    fs::write(&pointer, &original).unwrap();
    let source = TempDir::new();
    prepared_named(&source.0, "Repair map");
    install(&store, &source.0, true, &none()).unwrap();
    assert_eq!(
        store.active("us-il").unwrap().unwrap().record.metadata.name,
        "Repair map"
    );
    let mut journal = read_journal(&store).unwrap().unwrap();
    assert_eq!(journal.changes[0].before, Some(original.clone()));
    // The same opaque bytes must survive rollback of an uncommitted operation.
    journal.committed = false;
    write_journal(&store, &journal).unwrap();
    recover(&store).unwrap();
    assert_eq!(fs::read(pointer).unwrap(), original);
}

#[test]
fn recovery_releases_its_reserve_and_cleans_an_interrupted_preparation() {
    let target = TempDir::new();
    let store = PackageStore::new(&target.0);
    reserve_recovery_space(&store).unwrap();
    assert_eq!(
        fs::metadata(target.0.join(RESERVE)).unwrap().len(),
        64 * 1024
    );
    assert!(!recover(&store).unwrap());
    assert!(!target.0.join(RESERVE).exists());
    let journal = transaction(&store);
    reserve_recovery_space(&store).unwrap();
    write_journal(&store, &journal).unwrap();
    recover(&store).unwrap();
    assert!(!target.0.join(RESERVE).exists());
    assert!(!target.0.join(JOURNAL).exists());
}

#[test]
fn pruning_preserves_versions_if_the_active_pointer_is_missing_or_invalid() {
    let target = TempDir::new();
    let store = PackageStore::new(&target.0);
    let kept = store.version_dir("us-il", "keep");
    fs::create_dir_all(&kept).unwrap();
    fs::write(kept.join("package.mbtiles"), b"preserve this file").unwrap();
    for pointer in [
        None,
        Some("not json"),
        Some(r#"{"schema":1,"version":"missing"}"#),
    ] {
        if let Some(text) = pointer {
            fs::write(store.region_dir("us-il").join("active.json"), text).unwrap();
        }
        assert!(store.prune("us-il").is_empty());
        assert_eq!(
            fs::read(kept.join("package.mbtiles")).unwrap(),
            b"preserve this file"
        );
    }
}

#[test]
fn imports_replaces_and_keeps_open_old_readers_and_saved_places() {
    let target = TempDir::new();
    let store = PackageStore::new(&target.0);
    let source = TempDir::new();
    prepared_named(&source.0, "Old map");
    install(&store, &source.0, false, &none()).unwrap();
    recover(&store).unwrap();
    let old = store.active("us-il").unwrap().unwrap();
    let old_reader = crate::mbtiles::TilePackage::open(&old.path).unwrap();
    let old_search =
        crate::search::SearchIndex::open(&old.search_index_path(), &old.record.sha256).unwrap();
    fs::write(
        target.0.join("saved-places.json"),
        b"original personal data",
    )
    .unwrap();
    let source = TempDir::new();
    prepared_named(&source.0, "New map");
    assert!(install(&store, &source.0, false, &none()).is_err());
    assert_eq!(store.active("us-il").unwrap().unwrap().version, old.version);
    install(&store, &source.0, true, &none()).unwrap();
    let new = store.active("us-il").unwrap().unwrap();
    assert_ne!(new.version, old.version);
    assert_ne!(new.record.sha256, old.record.sha256);
    assert_eq!(new.record.metadata.name, "New map");
    assert!(old.path.is_file());
    old_reader.tile(0, 0, 0).unwrap();
    drop(old_search);
    crate::search::SearchIndex::open(&new.search_index_path(), &new.record.sha256).unwrap();
    assert!(recover(&store).unwrap());
    assert_eq!(store.active("us-il").unwrap().unwrap().version, new.version);
    assert!(!recover(&store).unwrap());
    assert_eq!(
        fs::read(target.0.join("saved-places.json")).unwrap(),
        b"original personal data"
    );
}

#[test]
fn absent_optional_layers_and_other_regions_are_preserved() {
    let target = TempDir::new();
    let store = PackageStore::new(&target.0);
    for name in [
        "us-il/extras/active.json",
        "us-il/terrain/active.json",
        "us-il/overlays/active.json",
        "us-in/active.json",
    ] {
        let path = target.0.join(name);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(path, "keep these bytes").unwrap();
    }
    let source = TempDir::new();
    prepared_named(&source.0, "New map");
    install(&store, &source.0, false, &none()).unwrap();
    recover(&store).unwrap();
    for name in [
        "us-il/extras/active.json",
        "us-il/terrain/active.json",
        "us-il/overlays/active.json",
        "us-in/active.json",
    ] {
        assert_eq!(
            fs::read_to_string(target.0.join(name)).unwrap(),
            "keep these bytes"
        );
    }
}

#[test]
fn damaged_or_cancelled_replacement_and_held_lock_keep_old_pointer() {
    let target = TempDir::new();
    let store = PackageStore::new(&target.0);
    let first = TempDir::new();
    prepared_named(&first.0, "Old map");
    install(&store, &first.0, false, &none()).unwrap();
    recover(&store).unwrap();
    let path = pointer_path(&store, "us-il", Layer::Map);
    let before = fs::read(&path).unwrap();
    let source = TempDir::new();
    prepared_named(&source.0, "New map");
    assert!(install(&store, &source.0, true, &AtomicBool::new(true)).is_err());
    let held = store.lock("us-il").unwrap();
    assert!(install(&store, &source.0, true, &none()).is_err());
    drop(held);
    fs::write(
        source.0.join("us-il/versions/v1/package.mbtiles"),
        b"damaged",
    )
    .unwrap();
    assert!(install(&store, &source.0, true, &none()).is_err());
    assert_eq!(fs::read(path).unwrap(), before);
    assert!(!store.root().join(JOURNAL).exists());
}

fn transaction(store: &PackageStore) -> Journal {
    let changes = [Layer::Map, Layer::Extras, Layer::Terrain, Layer::Overlays]
        .into_iter()
        .enumerate()
        .map(|(i, layer)| {
            let dir = store.region_dir(&layer.id("us-il"));
            fs::create_dir_all(dir.join("versions/old")).unwrap();
            fs::create_dir_all(dir.join("versions/import-new")).unwrap();
            for name in layer.files() {
                fs::write(dir.join("versions/old").join(name), b"old file").unwrap();
                fs::write(dir.join("versions/import-new").join(name), b"new file").unwrap();
            }
            let before = (i % 2 == 0).then(|| new_pointer("old").into_bytes());
            if let Some(value) = &before {
                fs::write(dir.join("active.json"), value).unwrap();
            }
            Change {
                layer,
                before,
                version: "import-new".into(),
            }
        })
        .collect();
    Journal {
        schema: 1,
        region: "us-il".into(),
        committed: false,
        changes,
    }
}

#[test]
fn recovery_restores_every_partial_pointer_set_after_process_interruption() {
    for written in 0..=4 {
        let target = TempDir::new();
        let store = PackageStore::new(&target.0);
        let journal = transaction(&store);
        write_journal(&store, &journal).unwrap();
        for change in journal.changes.iter().take(written) {
            fs::write(
                pointer_path(&store, "us-il", change.layer),
                new_pointer(&change.version),
            )
            .unwrap();
        }
        // Reopening uses only on-disk state, without the original transaction.
        let reopened = PackageStore::new(&target.0);
        assert!(recover(&reopened).unwrap());
        for change in &journal.changes {
            assert_eq!(
                read_pointer(&pointer_path(&reopened, "us-il", change.layer)).unwrap(),
                change.before
            );
            assert!(
                !reopened
                    .version_dir(&change.layer.id("us-il"), &change.version)
                    .exists()
            );
            assert!(
                reopened
                    .version_dir(&change.layer.id("us-il"), "old")
                    .exists()
            );
        }
    }
}

#[test]
fn committed_recovery_keeps_all_new_pointers() {
    let target = TempDir::new();
    let store = PackageStore::new(&target.0);
    let mut journal = transaction(&store);
    for change in &journal.changes {
        fs::write(
            pointer_path(&store, "us-il", change.layer),
            new_pointer(&change.version),
        )
        .unwrap();
    }
    journal.committed = true;
    write_journal(&store, &journal).unwrap();
    recover(&PackageStore::new(&target.0)).unwrap();
    for change in &journal.changes {
        assert_eq!(
            read_pointer(&pointer_path(&store, "us-il", change.layer)).unwrap(),
            Some(new_pointer(&change.version).into_bytes())
        );
        assert!(
            store
                .version_dir(&change.layer.id("us-il"), &change.version)
                .exists()
        );
    }
}

#[test]
fn pointer_write_failure_can_restore_changed_pointers_without_rewriting_unchanged_ones() {
    let target = TempDir::new();
    let store = PackageStore::new(&target.0);
    let journal = transaction(&store);
    write_journal(&store, &journal).unwrap();
    let blocked = pointer_path(&store, "us-il", Layer::Terrain).with_file_name("active.json.tmp");
    fs::create_dir(blocked).unwrap();
    let result = journal.changes.iter().try_for_each(|change| {
        write_synced(
            &pointer_path(&store, "us-il", change.layer),
            new_pointer(&change.version).as_bytes(),
        )
    });
    assert!(result.is_err());
    recover(&store).unwrap();
    for change in &journal.changes {
        assert_eq!(
            read_pointer(&pointer_path(&store, "us-il", change.layer)).unwrap(),
            change.before
        );
    }
}

#[test]
fn recovery_preserves_unexpected_subsequent_writer_and_rejects_invalid_journal() {
    let target = TempDir::new();
    let store = PackageStore::new(&target.0);
    let mut journal = transaction(&store);
    write_journal(&store, &journal).unwrap();
    let pointer = pointer_path(&store, "us-il", Layer::Map);
    fs::write(&pointer, new_pointer("external-update")).unwrap();
    assert!(recover(&store).is_err());
    assert_eq!(
        fs::read_to_string(&pointer).unwrap(),
        new_pointer("external-update")
    );
    journal.region = "../outside".into();
    write_journal(&store, &journal).unwrap();
    assert!(recover(&store).is_err());
    assert_eq!(
        fs::read_to_string(pointer).unwrap(),
        new_pointer("external-update")
    );
}

#[test]
fn cleanup_never_deletes_a_formatted_active_pointer_target() {
    let target = TempDir::new();
    let store = PackageStore::new(&target.0);
    let journal = transaction(&store);
    for change in &journal.changes {
        let pretty = serde_json::to_string_pretty(
            &serde_json::json!({"schema": 1, "version": change.version}),
        )
        .unwrap();
        fs::write(pointer_path(&store, "us-il", change.layer), pretty).unwrap();
    }
    cleanup_versions(&store, &journal);
    for change in &journal.changes {
        assert!(
            store
                .version_dir(&change.layer.id("us-il"), &change.version)
                .is_dir()
        );
    }
}
