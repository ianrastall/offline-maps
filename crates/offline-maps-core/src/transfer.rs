//! Verified, offline transfer of a prepared desktop region to Android.
//! A transfer contains only active files and a SHA-256 manifest. Imports add
//! regions; an existing region is never removed or overwritten.
use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicBool;

use rusqlite::{Connection, OpenFlags};
use serde::{Deserialize, Serialize};

use crate::extras::{active_extras, open_entries};
use crate::mbtiles::PackageError;
use crate::overlays::{active_overlays, verify_overlays};
use crate::package::{sha256_file, verify_package};
use crate::search::SearchIndex;
use crate::store::PackageStore;
use crate::terrain::{TerrainTiles, active_terrain};

pub const MANIFEST: &str = "transfer.json";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FileRecord {
    pub bytes: u64,
    pub sha256: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Transfer {
    pub schema: u32,
    pub region: String,
    pub files: BTreeMap<String, FileRecord>,
}

fn invalid(message: impl Into<String>) -> PackageError {
    PackageError::Invalid(message.into())
}

impl Transfer {
    pub fn read(root: &Path) -> Result<Self, PackageError> {
        let path = root.join(MANIFEST);
        if fs::metadata(&path)?.len() > 64 * 1024 {
            return Err(invalid("The transfer manifest is too large."));
        }
        let value: Self = serde_json::from_slice(&fs::read(path)?)
            .map_err(|e| invalid(format!("The transfer manifest is unreadable: {e}")))?;
        if value.schema != 1 || crate::regions::find(&value.region).is_none() {
            return Err(invalid("Unknown transfer format or region."));
        }
        if !(4..=13).contains(&value.files.len()) {
            return Err(invalid("The transfer does not list a prepared region."));
        }
        for (name, record) in &value.files {
            if name.split('/').any(|p| {
                p.is_empty()
                    || p == "."
                    || p == ".."
                    || !p
                        .bytes()
                        .all(|c| c.is_ascii_alphanumeric() || b"-_.".contains(&c))
            }) || !name.starts_with(&format!("{}/", value.region))
                || record.bytes == 0
                || record.sha256.len() != 64
                || !record.sha256.bytes().all(|c| c.is_ascii_hexdigit())
            {
                return Err(invalid("Invalid transfer file name, size or checksum."));
            }
        }
        Ok(value)
    }
}

fn regular(root: &Path, name: &str) -> Result<PathBuf, PackageError> {
    let mut path = root.to_path_buf();
    for part in name.split('/') {
        path.push(part);
        if fs::symlink_metadata(&path)?.file_type().is_symlink() {
            return Err(invalid("Transfer files cannot be symbolic links."));
        }
    }
    if !fs::metadata(&path)?.is_file() {
        return Err(invalid("A transfer file is not a regular file."));
    }
    Ok(path)
}

fn integrity(path: &Path) -> Result<(), PackageError> {
    let db = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)?;
    let result: String = db.query_row("PRAGMA integrity_check", [], |r| r.get(0))?;
    if result != "ok" {
        return Err(invalid(format!(
            "A transferred database is damaged: {result}"
        )));
    }
    Ok(())
}

/// Checks checksums, coverage, formats, search/package association and every
/// optional layer before returning the prepared region's ID. Nothing is changed.
pub fn verify(root: &Path, cancelled: &AtomicBool) -> Result<String, PackageError> {
    let manifest = Transfer::read(root)?;
    for (name, record) in &manifest.files {
        let path = regular(root, name)?;
        if fs::metadata(&path)?.len() != record.bytes {
            return Err(invalid(format!("{name} has an unexpected size.")));
        }
        let (hash, _) = sha256_file(&path, cancelled)?;
        if !hash.eq_ignore_ascii_case(&record.sha256) {
            return Err(invalid(format!("{name} failed its checksum.")));
        }
    }
    let store = PackageStore::new(root);
    let id = manifest.region.as_str();
    let region = crate::regions::find(id).expect("validated region");
    let active = store.active(id)?.ok_or_else(|| invalid("No active map."))?;
    let record = verify_package(
        &active.path,
        region,
        active.record.source.clone(),
        &active.record.downloaded_at,
        &active.record.verified_at,
        cancelled,
    )?;
    if record.sha256 != active.record.sha256 {
        return Err(invalid("The map does not match its desktop record."));
    }
    let mut expected = vec![
        store.region_dir(id).join("active.json"),
        active.path.clone(),
        active.path.with_file_name("record.json"),
        active.search_index_path(),
    ];
    integrity(&active.search_index_path())?;
    let mut search = SearchIndex::open(&active.search_index_path(), &record.sha256)?;
    search.stats()?;
    if let Some(extra) = active_extras(&store, id)? {
        integrity(&extra.path)?;
        open_entries(&extra.path)?;
        search.attach_extras(&extra.path)?;
        expected.extend([
            store.region_dir(id).join("extras/active.json"),
            extra.path.with_file_name("record.json"),
            extra.path,
        ]);
    }
    if let Some(terrain) = active_terrain(&store, id)? {
        integrity(&terrain.path)?;
        if terrain.record.max_zoom > crate::terrain::TERRAIN_MAX_ZOOM
            || !region.outer.contains(&terrain.record.bounds)
        {
            return Err(invalid("The terrain has invalid coverage or zooms."));
        }
        TerrainTiles::open(&terrain.path)?.tile(0, 0, 0)?;
        expected.extend([
            store.region_dir(id).join("terrain/active.json"),
            terrain.path.with_file_name("record.json"),
            terrain.path,
        ]);
    }
    if let Some(overlays) = active_overlays(&store, id)? {
        verify_overlays(&overlays.path, region)?;
        if sha256_file(&overlays.path, cancelled)?.0 != overlays.record.sha256 {
            return Err(invalid("The overlays do not match their desktop record."));
        }
        expected.extend([
            store.region_dir(id).join("overlays/active.json"),
            overlays.path.with_file_name("record.json"),
            overlays.path,
        ]);
    }
    let mut names: Vec<_> = expected
        .iter()
        .map(|p| {
            p.strip_prefix(root)
                .expect("store path")
                .to_string_lossy()
                .replace('\\', "/")
        })
        .collect();
    names.sort();
    if names != manifest.files.keys().cloned().collect::<Vec<_>>() {
        return Err(invalid(
            "The transfer must list exactly its active map, search and layer files.",
        ));
    }
    Ok(manifest.region)
}

/// Adds a verified region in one directory rename on the destination volume.
/// A failure or interruption leaves all installed regions and saved places intact.
pub fn install(
    store: &PackageStore,
    staged: &Path,
    cancelled: &AtomicBool,
) -> Result<String, PackageError> {
    let id = Transfer::read(staged)?.region;
    if store.region_dir(&id).exists() {
        return Err(invalid(
            "That region is already installed. Existing maps are kept.",
        ));
    }
    let _lock = store.lock("transfer")?;
    verify(staged, cancelled)?;
    if cancelled.load(std::sync::atomic::Ordering::Relaxed) {
        return Err(invalid("Import cancelled."));
    }
    // Recheck under the lock, immediately before activation.
    if store.region_dir(&id).exists() {
        return Err(invalid("That region is already installed."));
    }
    fs::rename(staged.join(&id), store.region_dir(&id))?;
    Ok(id)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mbtiles::tests::TempFile;

    struct TempDir(PathBuf);
    impl TempDir {
        fn new() -> Self {
            let path = TempFile::new("transfer").0.clone();
            fs::create_dir_all(&path).unwrap();
            Self(path)
        }
    }
    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    fn manifest(root: &Path, bad_name: &str) {
        let mut files = BTreeMap::new();
        for name in [bad_name, "us-il/a", "us-il/b", "us-il/c"] {
            files.insert(
                name.into(),
                FileRecord {
                    bytes: 1,
                    sha256: "a".repeat(64),
                },
            );
        }
        fs::write(
            root.join(MANIFEST),
            serde_json::to_vec(&Transfer {
                schema: 1,
                region: "us-il".into(),
                files,
            })
            .unwrap(),
        )
        .unwrap();
    }
    #[test]
    fn rejects_traversal_absolute_windows_paths_and_wrong_regions() {
        let root = TempDir::new();
        for bad in [
            "../us-il/x",
            "/us-il/x",
            "us-il/../x",
            "us-il/a\\b",
            "us-il/C:x",
            "us-in/x",
        ] {
            manifest(&root.0, bad);
            assert!(Transfer::read(&root.0).is_err(), "{bad}");
        }
    }
    #[test]
    fn damaged_transfer_never_changes_installed_data() {
        let source = TempDir::new();
        let target = TempDir::new();
        fs::write(target.0.join("saved-places.json"), "keep me").unwrap();
        manifest(&source.0, "us-il/x");
        assert!(
            install(
                &PackageStore::new(&target.0),
                &source.0,
                &AtomicBool::new(false)
            )
            .is_err()
        );
        assert!(!target.0.join("us-il").exists());
        assert_eq!(
            fs::read_to_string(target.0.join("saved-places.json")).unwrap(),
            "keep me"
        );
        fs::create_dir(target.0.join("us-il")).unwrap();
        assert!(
            install(
                &PackageStore::new(&target.0),
                &source.0,
                &AtomicBool::new(false)
            )
            .unwrap_err()
            .to_string()
            .contains("already installed")
        );
    }
    #[test]
    fn rejects_mismatching_hash_before_opening_database() {
        let source = TempDir::new();
        manifest(&source.0, "us-il/x");
        fs::create_dir(source.0.join("us-il")).unwrap();
        fs::write(source.0.join("us-il/a"), "z").unwrap();
        let err = verify(&source.0, &AtomicBool::new(false)).unwrap_err();
        assert!(err.to_string().contains("checksum"));
    }

    fn prepared(root: &Path) {
        use crate::mbtiles::TilePackage;
        use crate::mbtiles::tests::{build_package, valid_metadata};
        let package = build_package(&valid_metadata(), &[(0, 0, 0, &[]), (1, 0, 0, &[])]);
        let none = AtomicBool::new(false);
        let record = verify_package(
            &package.0,
            &crate::package::ILLINOIS,
            Default::default(),
            "today",
            "today",
            &none,
        )
        .unwrap();
        let index = root.join("search.sqlite");
        crate::search::build_index(
            &TilePackage::open(&package.0).unwrap(),
            &record.sha256,
            &index,
            &none,
            &mut |_, _| {},
        )
        .unwrap();
        let store = PackageStore::new(root);
        store
            .install_version("us-il", "v1", &package.0, Some(&index), &record)
            .unwrap();
        store.activate("us-il", "v1").unwrap();
        let mut files = BTreeMap::new();
        for name in [
            "us-il/active.json",
            "us-il/versions/v1/record.json",
            "us-il/versions/v1/package.mbtiles",
            "us-il/versions/v1/search.sqlite",
        ] {
            let (sha256, bytes) = sha256_file(&root.join(name), &none).unwrap();
            files.insert(name.into(), FileRecord { bytes, sha256 });
        }
        fs::write(
            root.join(MANIFEST),
            serde_json::to_vec(&Transfer {
                schema: 1,
                region: "us-il".into(),
                files,
            })
            .unwrap(),
        )
        .unwrap();
    }
    #[test]
    fn activates_complete_region_and_preserves_previous_on_duplicate() {
        let source = TempDir::new();
        let target = TempDir::new();
        prepared(&source.0);
        assert_eq!(verify(&source.0, &AtomicBool::new(false)).unwrap(), "us-il");
        let store = PackageStore::new(&target.0);
        install(&store, &source.0, &AtomicBool::new(false)).unwrap();
        assert!(store.active("us-il").unwrap().is_some());
        assert!(install(&store, &source.0, &AtomicBool::new(false)).is_err());
        assert!(store.active("us-il").unwrap().is_some());
    }
    #[test]
    fn cancellation_leaves_no_active_region() {
        let source = TempDir::new();
        let target = TempDir::new();
        prepared(&source.0);
        assert!(
            install(
                &PackageStore::new(&target.0),
                &source.0,
                &AtomicBool::new(true)
            )
            .is_err()
        );
        assert!(!target.0.join("us-il").exists());
        assert!(source.0.join("us-il/active.json").exists());
    }
}
