//! Verified, offline transfer of a prepared desktop region to Android.
//! A transfer contains only active files and a SHA-256 manifest. Imports add
//! regions; an existing region is never removed or overwritten.
use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};

use rusqlite::{Connection, OpenFlags};
use serde::{Deserialize, Serialize};

use crate::extras::{ActiveExtras, active_extras, open_entries};
use crate::mbtiles::PackageError;
use crate::overlays::{ActiveOverlays, active_overlays, verify_overlays};
use crate::package::{sha256_file, verify_package};
use crate::search::SearchIndex;
use crate::store::{ActivePackage, PackageStore};
use crate::terrain::{ActiveTerrain, TerrainTiles, active_terrain};

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

struct RegionFiles {
    map: ActivePackage,
    extras: Option<ActiveExtras>,
    terrain: Option<ActiveTerrain>,
    overlays: Option<ActiveOverlays>,
}

impl RegionFiles {
    fn open(store: &PackageStore, id: &str) -> Result<Self, PackageError> {
        Ok(Self {
            map: store.active(id)?.ok_or_else(|| invalid("No active map."))?,
            extras: active_extras(store, id)?,
            terrain: active_terrain(store, id)?,
            overlays: active_overlays(store, id)?,
        })
    }

    fn paths(&self, store: &PackageStore, id: &str) -> Vec<PathBuf> {
        let region = store.region_dir(id);
        let mut paths = vec![
            region.join("active.json"),
            self.map.path.clone(),
            self.map.path.with_file_name("record.json"),
            self.map.search_index_path(),
        ];
        for (layer, file) in [
            ("extras", self.extras.as_ref().map(|e| &e.path)),
            ("terrain", self.terrain.as_ref().map(|t| &t.path)),
            ("overlays", self.overlays.as_ref().map(|o| &o.path)),
        ] {
            if let Some(file) = file {
                paths.extend([
                    region.join(layer).join("active.json"),
                    file.with_file_name("record.json"),
                    file.clone(),
                ]);
            }
        }
        paths
    }
}

fn relative_name(root: &Path, file: &Path) -> String {
    file.strip_prefix(root)
        .expect("store path")
        .to_string_lossy()
        .replace('\\', "/")
}

/// Describe the files in one installed region. The caller keeps updates
/// blocked until it has checked every exported file against this manifest.
pub fn describe_export(
    store: &PackageStore,
    id: &str,
    cancelled: &AtomicBool,
) -> Result<Transfer, PackageError> {
    if crate::regions::find(id).is_none() {
        return Err(invalid("Unknown map region."));
    }
    let mut files = BTreeMap::new();
    let active = RegionFiles::open(store, id)?;
    for file in active.paths(store, id) {
        let name = relative_name(store.root(), &file);
        let (sha256, bytes) = sha256_file(&regular(store.root(), &name)?, cancelled)?;
        if bytes == 0 {
            return Err(invalid(format!("{name} is empty.")));
        }
        if file == active.map.path && sha256 != active.map.record.sha256 {
            return Err(invalid("The installed map failed its checksum."));
        }
        files.insert(name, FileRecord { bytes, sha256 });
    }
    Ok(Transfer {
        schema: 1,
        region: id.into(),
        files,
    })
}

/// Copy installed regions into a new library that Android can restore.
/// The caller excludes source updates while the library is being made.
pub fn export_library(
    store: &PackageStore,
    parent: &Path,
    regions: &[String],
    cancelled: &AtomicBool,
    mut progress: impl FnMut(usize, usize, &str),
) -> Result<PathBuf, PackageError> {
    let unique: BTreeSet<_> = regions.iter().collect();
    if unique.is_empty() || unique.len() != regions.len() {
        return Err(invalid("Select one or more different installed maps."));
    }
    for id in regions {
        if crate::regions::find(id).is_none() || store.active(id)?.is_none() {
            return Err(invalid("A selected map is no longer installed."));
        }
    }
    if !parent.is_absolute()
        || fs::canonicalize(parent)?.starts_with(fs::canonicalize(store.root())?)
    {
        return Err(invalid(
            "Choose an export folder outside the installed map store.",
        ));
    }
    check_cancelled(cancelled)?;
    let timestamp = crate::time::utc_now().replace(':', "-");
    let staging = ExportStaging::create(
        parent,
        &format!(".offline-maps-export-{}-{timestamp}", std::process::id()),
    )?;
    let library = staging.0.join("library");
    fs::create_dir(&library)?;
    let mut buffer = vec![0; 1024 * 1024];
    for (done, id) in regions.iter().enumerate() {
        check_cancelled(cancelled)?;
        progress(done, regions.len(), id);
        let manifest = describe_export(store, id, cancelled)?;
        let region = library.join(id);
        fs::create_dir(&region)?;
        for name in manifest.files.keys() {
            let source = regular(store.root(), name)?;
            let target = region.join(name);
            fs::create_dir_all(target.parent().expect("transfer file parent"))?;
            copy_export_file(&source, &target, &mut buffer, cancelled)?;
        }
        let json = serde_json::to_vec_pretty(&manifest)
            .map_err(|e| invalid(format!("Could not write a transfer manifest: {e}")))?;
        fs::write(region.join(MANIFEST), json)?;
        verify(&region, cancelled)?;
    }
    check_cancelled(cancelled)?;
    let name = format!("Offline Maps Library {timestamp}");
    for suffix in 1.. {
        let path = export_path(parent, &name, suffix);
        if path.symlink_metadata().is_ok() {
            continue;
        }
        match fs::rename(&library, &path) {
            Ok(()) => return Ok(path),
            Err(_) if path.symlink_metadata().is_ok() => continue,
            Err(e) => return Err(e.into()),
        }
    }
    unreachable!("unbounded export name selection")
}

fn check_cancelled(cancelled: &AtomicBool) -> io::Result<()> {
    if cancelled.load(Ordering::Relaxed) {
        return Err(io::Error::new(
            io::ErrorKind::Interrupted,
            "Map export cancelled.",
        ));
    }
    Ok(())
}

fn copy_export_file(
    source: &Path,
    target: &Path,
    buffer: &mut [u8],
    cancelled: &AtomicBool,
) -> io::Result<()> {
    let mut input = File::open(source)?;
    let mut output = File::create_new(target)?;
    loop {
        check_cancelled(cancelled)?;
        let read = input.read(buffer)?;
        if read == 0 {
            return output.sync_all();
        }
        output.write_all(&buffer[..read])?;
    }
}

fn export_path(parent: &Path, name: &str, suffix: u32) -> PathBuf {
    if suffix == 1 {
        parent.join(name)
    } else {
        parent.join(format!("{name} ({suffix})"))
    }
}

struct ExportStaging(PathBuf);

impl ExportStaging {
    fn create(parent: &Path, name: &str) -> io::Result<Self> {
        for suffix in 1.. {
            let path = export_path(parent, name, suffix);
            match fs::create_dir(&path) {
                Ok(()) => return Ok(Self(path)),
                Err(e) if e.kind() == io::ErrorKind::AlreadyExists => continue,
                Err(e) => return Err(e),
            }
        }
        unreachable!("unbounded staging name selection")
    }
}

impl Drop for ExportStaging {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
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
    let region_files = RegionFiles::open(&store, id)?;
    let active = &region_files.map;
    let record = verify_package(
        &active.path,
        region,
        active.record.source.clone(),
        &active.record.downloaded_at,
        &active.record.verified_at,
        cancelled,
    )?;
    if record.sha256 != active.record.sha256
        || record.metadata != active.record.metadata
        || record.check != active.record.check
    {
        return Err(invalid("The map does not match its desktop record."));
    }
    integrity(&active.search_index_path())?;
    let mut search = SearchIndex::open(&active.search_index_path(), &record.sha256)?;
    search.stats()?;
    if let Some(extra) = &region_files.extras {
        integrity(&extra.path)?;
        open_entries(&extra.path)?;
        search.attach_extras(&extra.path)?;
    }
    if let Some(terrain) = &region_files.terrain {
        integrity(&terrain.path)?;
        if terrain.record.max_zoom > crate::terrain::TERRAIN_MAX_ZOOM
            || !region.outer.contains(&terrain.record.bounds)
        {
            return Err(invalid("The terrain has invalid coverage or zooms."));
        }
        TerrainTiles::open(&terrain.path)?.tile(0, 0, 0)?;
    }
    if let Some(overlays) = &region_files.overlays {
        verify_overlays(&overlays.path, region)?;
        if sha256_file(&overlays.path, cancelled)?.0 != overlays.record.sha256 {
            return Err(invalid("The overlays do not match their desktop record."));
        }
    }
    let mut names: Vec<_> = region_files
        .paths(&store, id)
        .iter()
        .map(|p| relative_name(root, p))
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
pub(crate) mod tests {
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

    pub(crate) fn prepared(root: &Path) {
        prepared_named(root, "Original map");
    }

    pub(crate) fn prepared_named(root: &Path, name: &'static str) {
        use crate::mbtiles::TilePackage;
        use crate::mbtiles::tests::{build_package, valid_metadata};
        let mut metadata = valid_metadata();
        metadata.retain(|(key, _)| *key != "name");
        metadata.push(("name", name));
        let package = build_package(&metadata, &[(0, 0, 0, &[]), (1, 0, 0, &[])]);
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
    fn describes_a_restore_ready_copy_and_rejects_a_changed_map() {
        let source = TempDir::new();
        prepared(&source.0);
        let store = PackageStore::new(&source.0);
        let running = AtomicBool::new(false);
        let manifest = describe_export(&store, "us-il", &running).unwrap();
        assert_eq!(
            serde_json::to_value(manifest).unwrap(),
            serde_json::to_value(Transfer::read(&source.0).unwrap()).unwrap()
        );
        let map = store.active("us-il").unwrap().unwrap().path;
        let mut bytes = fs::read(&map).unwrap();
        let changed = bytes.last_mut().unwrap();
        *changed ^= 1;
        fs::write(&map, bytes).unwrap();
        assert!(describe_export(&store, "us-il", &running).is_err());
    }

    #[test]
    fn exports_verified_library_without_overwriting_and_cleans_up_failed_copy() {
        let source = TempDir::new();
        let destination = TempDir::new();
        prepared(&source.0);
        let store = PackageStore::new(&source.0);
        let cancel = AtomicBool::new(false);
        let map = vec!["us-il".to_owned()];
        let first = export_library(&store, &destination.0, &map, &cancel, |_, _, _| {}).unwrap();
        let second = export_library(&store, &destination.0, &map, &cancel, |_, _, _| {}).unwrap();
        assert_ne!(first, second);
        for library in [first, second] {
            assert_eq!(verify(&library.join("us-il"), &cancel).unwrap(), "us-il");
        }
        assert!(export_library(&store, &source.0, &map, &cancel, |_, _, _| {}).is_err());
        assert!(
            export_library(&store, &destination.0, &map, &cancel, |_, _, _| {
                cancel.store(true, Ordering::Relaxed);
            })
            .is_err()
        );
        assert_eq!(fs::read_dir(&destination.0).unwrap().count(), 2);
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
