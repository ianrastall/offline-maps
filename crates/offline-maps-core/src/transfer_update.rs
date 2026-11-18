//! Replacement of verified offline transfers. New versions are complete before
//! pointers change; a journal restores an interrupted, uncommitted pointer set.
use std::collections::BTreeSet;
use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

use serde::{Deserialize, Serialize};

use crate::extras::active_extras;
use crate::mbtiles::PackageError;
use crate::overlays::active_overlays;
use crate::store::{PackageStore, is_version_name, write_atomic};
use crate::terrain::active_terrain;
use crate::transfer::{Transfer, verify};

const JOURNAL: &str = ".transfer-update.json";
const RESERVE: &str = ".transfer-update.reserve";
static NEXT: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
enum Layer {
    Map,
    Extras,
    Terrain,
    Overlays,
}

impl Layer {
    fn id(self, region: &str) -> String {
        match self {
            Self::Map => region.to_owned(),
            Self::Extras => format!("{region}/extras"),
            Self::Terrain => format!("{region}/terrain"),
            Self::Overlays => format!("{region}/overlays"),
        }
    }
    fn files(self) -> &'static [&'static str] {
        match self {
            Self::Map => &["package.mbtiles", "search.sqlite", "record.json"],
            Self::Extras => &["extras.sqlite", "record.json"],
            Self::Terrain => &["terrain.mbtiles", "record.json"],
            Self::Overlays => &["overlays.mbtiles", "record.json"],
        }
    }
}

#[derive(Debug, Serialize, Deserialize)]
struct Change {
    layer: Layer,
    before: Option<Vec<u8>>,
    version: String,
}

#[derive(Debug, Serialize, Deserialize)]
struct Journal {
    schema: u32,
    region: String,
    committed: bool,
    changes: Vec<Change>,
}

fn invalid(message: impl Into<String>) -> PackageError {
    PackageError::Invalid(message.into())
}

fn pointer_path(store: &PackageStore, region: &str, layer: Layer) -> PathBuf {
    store.region_dir(&layer.id(region)).join("active.json")
}

fn read_pointer(path: &Path) -> Result<Option<Vec<u8>>, PackageError> {
    match fs::metadata(path) {
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e.into()),
        Ok(meta) if meta.len() > 4096 => {
            return Err(invalid("An installed map pointer is too large."));
        }
        Ok(_) => {}
    }
    Ok(Some(fs::read(path)?))
}

fn new_pointer(version: &str) -> String {
    serde_json::json!({"schema": 1, "version": version}).to_string()
}

fn sync_dir(path: &Path) -> io::Result<()> {
    #[cfg(unix)]
    {
        fs::File::open(path)?.sync_all()?;
    }
    #[cfg(not(unix))]
    {
        let _ = path;
    }
    Ok(())
}

fn write_synced(path: &Path, bytes: &[u8]) -> io::Result<()> {
    write_atomic(path, bytes)?;
    sync_dir(path.parent().expect("pointer parent"))
}

fn read_journal(store: &PackageStore) -> Result<Option<Journal>, PackageError> {
    let path = store.root().join(JOURNAL);
    match fs::symlink_metadata(&path) {
        Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(None),
        Err(e) => return Err(e.into()),
        Ok(meta) if meta.len() > 128 * 1024 => {
            return Err(invalid("The transfer recovery record is too large."));
        }
        Ok(meta) if !meta.is_file() => {
            return Err(invalid(
                "The transfer recovery record is not a regular file.",
            ));
        }
        Ok(_) => {}
    }
    let journal: Journal = serde_json::from_slice(&fs::read(path)?).map_err(|_| {
        invalid("The transfer recovery record cannot be read. Existing files were kept.")
    })?;
    let layers: BTreeSet<_> = journal.changes.iter().map(|c| c.layer).collect();
    if journal.schema != 1
        || crate::regions::find(&journal.region).is_none()
        || !(1..=4).contains(&journal.changes.len())
        || layers.len() != journal.changes.len()
        || !layers.contains(&Layer::Map)
        || journal.changes.iter().any(|c| {
            !is_version_name(&c.version) || c.before.as_ref().is_some_and(|s| s.len() > 4096)
        })
    {
        return Err(invalid(
            "The transfer recovery record is invalid. Existing files were kept.",
        ));
    }
    Ok(Some(journal))
}

fn write_journal(store: &PackageStore, journal: &Journal) -> Result<(), PackageError> {
    let bytes = serde_json::to_vec(journal).map_err(io::Error::other)?;
    if bytes.len() > 128 * 1024 {
        return Err(invalid("The transfer recovery record is too large."));
    }
    write_synced(&store.root().join(JOURNAL), &bytes)?;
    Ok(())
}

fn release_reserve(store: &PackageStore) -> io::Result<()> {
    match fs::remove_file(store.root().join(RESERVE)) {
        Err(e) if e.kind() != io::ErrorKind::NotFound => Err(e),
        _ => Ok(()),
    }
}

fn reserve_recovery_space(store: &PackageStore) -> io::Result<()> {
    release_reserve(store)?;
    let mut file = fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(store.root().join(RESERVE))?;
    file.write_all(&[0u8; 64 * 1024])?;
    file.sync_all()?;
    sync_dir(store.root())
}

fn cleanup_versions(store: &PackageStore, journal: &Journal) {
    for change in &journal.changes {
        // All paths come from a catalog region, a fixed layer and a validated
        // fresh version name. Never remove an active version during cleanup.
        let pointer = pointer_path(store, &journal.region, change.layer);
        let inactive = match read_pointer(&pointer) {
            Ok(None) => true,
            Ok(Some(text)) => serde_json::from_slice::<serde_json::Value>(&text)
                .ok()
                .and_then(|v| {
                    v.get("version")
                        .and_then(|v| v.as_str())
                        .map(|v| v != change.version)
                })
                .unwrap_or(false),
            Err(_) => false,
        };
        if inactive {
            let _ = fs::remove_dir_all(
                store.version_dir(&change.layer.id(&journal.region), &change.version),
            );
        }
    }
}

/// Caller holds the transaction lock and all affected layer locks.
fn restore(store: &PackageStore, journal: &Journal) -> Result<(), PackageError> {
    if !journal.committed {
        // Refuse to overwrite a subsequent writer's pointer. Check every path
        // before changing any, so unexpected external changes are preserved.
        for change in &journal.changes {
            let current = read_pointer(&pointer_path(store, &journal.region, change.layer))?;
            if current != change.before
                && current.as_deref() != Some(new_pointer(&change.version).as_bytes())
            {
                return Err(invalid(
                    "The map store changed during transfer recovery. Existing files were kept.",
                ));
            }
        }
        for change in &journal.changes {
            let path = pointer_path(store, &journal.region, change.layer);
            if read_pointer(&path)? == change.before {
                continue;
            }
            if let Some(before) = &change.before {
                write_synced(&path, before)?;
            } else {
                match fs::remove_file(&path) {
                    Err(e) if e.kind() != io::ErrorKind::NotFound => return Err(e.into()),
                    _ => {}
                }
                sync_dir(path.parent().expect("pointer parent"))?;
            }
        }
    }
    fs::remove_file(store.root().join(JOURNAL))?;
    sync_dir(store.root())?;
    if !journal.committed {
        cleanup_versions(store, journal);
    }
    Ok(())
}

fn recover_locked(store: &PackageStore) -> Result<bool, PackageError> {
    let Some(journal) = read_journal(store)? else {
        release_reserve(store)?;
        return Ok(false);
    };
    let _locks: Vec<_> = journal
        .changes
        .iter()
        .map(|c| store.lock(&c.layer.id(&journal.region)))
        .collect::<Result<_, _>>()?;
    // Free reserved blocks/inode before rollback, even when the volume filled
    // after copying. Recovery only needs small pointer/metadata writes.
    release_reserve(store)?;
    restore(store, &journal)?;
    Ok(true)
}

/// Run before opening map readers and before starting another mutation. An
/// uncommitted transaction restores its old pointers; a committed one is kept.
pub fn recover(store: &PackageStore) -> Result<bool, PackageError> {
    match fs::symlink_metadata(store.root().join(JOURNAL)) {
        Err(e) if e.kind() == io::ErrorKind::NotFound => {
            match fs::symlink_metadata(store.root().join(RESERVE)) {
                Err(e) if e.kind() == io::ErrorKind::NotFound => return Ok(false),
                Err(e) => return Err(e.into()),
                Ok(_) => {}
            }
        }
        Err(e) => return Err(e.into()),
        Ok(_) => {}
    }
    let _lock = store.lock("transfer")?;
    recover_locked(store)
}

/// Consumes a private, staged copy. The original desktop/SAF folder is untouched.
/// All files are verified before any installed pointer changes. Missing optional
/// layers are retained. Existing maps require explicit replacement permission.
pub fn install(
    store: &PackageStore,
    staged: &Path,
    replace: bool,
    cancelled: &AtomicBool,
) -> Result<String, PackageError> {
    let _lock = store.lock("transfer")?;
    recover_locked(store)?;
    let region = Transfer::read(staged)?.region;
    if pointer_path(store, &region, Layer::Map).exists() && !replace {
        return Err(invalid(
            "Review and confirm replacing the installed region first.",
        ));
    }
    verify(staged, cancelled)?;
    let source = PackageStore::new(staged);
    let map = source.active(&region)?.expect("verified map");
    let mut incoming = vec![(Layer::Map, map.path)];
    if let Some(v) = active_extras(&source, &region)? {
        incoming.push((Layer::Extras, v.path));
    }
    if let Some(v) = active_terrain(&source, &region)? {
        incoming.push((Layer::Terrain, v.path));
    }
    if let Some(v) = active_overlays(&source, &region)? {
        incoming.push((Layer::Overlays, v.path));
    }
    let _locks: Vec<_> = incoming
        .iter()
        .map(|(layer, _)| store.lock(&layer.id(&region)))
        .collect::<Result<_, _>>()?;
    if pointer_path(store, &region, Layer::Map).exists() && !replace {
        return Err(invalid(
            "The region was installed while the transfer was being checked. Review it again.",
        ));
    }
    let time = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64;
    let version = format!(
        "import-{time:x}-{:x}-{:x}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    );
    let mut journal = Journal {
        schema: 1,
        region: region.clone(),
        committed: false,
        changes: Vec::new(),
    };
    let result = (|| -> Result<(), PackageError> {
        reserve_recovery_space(store)?;
        for (layer, path) in incoming {
            if cancelled.load(Ordering::Relaxed) {
                return Err(invalid("Import cancelled."));
            }
            let before = read_pointer(&pointer_path(store, &region, layer))?;
            let target = store.version_dir(&layer.id(&region), &version);
            fs::create_dir_all(target.parent().expect("versions directory"))?;
            fs::create_dir(&target)?;
            journal.changes.push(Change {
                layer,
                before,
                version: version.clone(),
            });
            for name in layer.files() {
                fs::rename(path.with_file_name(name), target.join(name))?;
            }
            sync_dir(&target)?;
            sync_dir(target.parent().expect("versions directory"))?;
        }
        if cancelled.load(Ordering::Relaxed) {
            return Err(invalid("Import cancelled."));
        }
        write_journal(store, &journal)?;
        // Once the short commit starts, finish it even if Cancel races with it.
        for change in &journal.changes {
            write_synced(
                &pointer_path(store, &region, change.layer),
                new_pointer(&change.version).as_bytes(),
            )?;
        }
        journal.committed = true;
        write_journal(store, &journal)?;
        Ok(())
    })();
    if let Err(e) = result {
        release_reserve(store)?;
        if let Some(saved) = read_journal(store)? {
            restore(store, &saved)
                .map_err(|recovery| invalid(format!("{e} Recovery is pending: {recovery}")))?;
        } else {
            cleanup_versions(store, &journal);
        }
        return Err(e);
    }
    // Keep the committed marker until the caller/reopening code confirms it.
    // Old versions remain available until that recovery record is removed.
    Ok(region)
}

#[cfg(test)]
#[path = "transfer_update_tests.rs"]
mod tests;
