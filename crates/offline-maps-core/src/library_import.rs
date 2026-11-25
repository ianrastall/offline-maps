//! Import of a map library folder (an Android backup, a desktop export or the
//! published map packages) into a store. Each region folder is copied into a
//! private staging folder on the store's volume, verified and installed with
//! [`transfer_update::install`]. Installed maps and layers are never replaced:
//! regions already installed are skipped, and separate layers (such as
//! terrain) are added only beside an installed map that lacks them.
//!
//! ```text
//! <library>/<any name>/transfer.json      one region or its separate layers
//! <library>/<any name>/<region>/...       files listed in transfer.json
//! ```
use std::fs::{self, File};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};

use crate::mbtiles::PackageError;
use crate::store::PackageStore;
use crate::transfer::{MANIFEST, Transfer, regular};
use crate::transfer_update;

/// What an import did, by region ID.
#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub struct LibraryImport {
    /// Whole regions that were added.
    pub added: Vec<String>,
    /// Regions that gained separate layers.
    pub layers_added: Vec<String>,
    /// Folders whose region or layers were already installed.
    pub skipped: usize,
    /// Folders of separate layers whose region's map is not installed.
    pub needs_map: Vec<String>,
    /// Folders that could not be imported, with the reason.
    pub failed: Vec<(String, String)>,
}

static NEXT: AtomicU64 = AtomicU64::new(0);

struct Candidate {
    dir: PathBuf,
    manifest: Transfer,
    layers_only: bool,
}

/// Region folders in `library`: the folder itself when it holds a transfer
/// manifest, otherwise its subfolders that do. Whole regions come before
/// separate layers, so a package and its terrain can be imported together.
/// Folder names with the reason each could not be read.
type Unreadable = Vec<(String, String)>;

fn candidates(library: &Path) -> Result<(Vec<Candidate>, Unreadable), PackageError> {
    let mut dirs = Vec::new();
    // The folder the published archives extract to, when its parent is chosen.
    let nested = library.join("Offline Maps Library");
    let library = if !library.join(MANIFEST).is_file() && nested.is_dir() {
        nested.as_path()
    } else {
        library
    };
    if library.join(MANIFEST).is_file() {
        dirs.push(library.to_path_buf());
    } else {
        for entry in fs::read_dir(library)? {
            let entry = entry?;
            if entry.file_type()?.is_dir() && entry.path().join(MANIFEST).is_file() {
                dirs.push(entry.path());
            }
        }
    }
    dirs.sort();
    let mut found = Vec::new();
    let mut failed = Vec::new();
    for dir in dirs {
        match Transfer::read(&dir) {
            Ok(manifest) => {
                let layers_only = !manifest
                    .files
                    .contains_key(&format!("{}/active.json", manifest.region));
                found.push(Candidate {
                    dir,
                    manifest,
                    layers_only,
                });
            }
            Err(e) => failed.push((folder_name(&dir), e.to_string())),
        }
    }
    found.sort_by_key(|c| c.layers_only);
    Ok((found, failed))
}

fn folder_name(dir: &Path) -> String {
    dir.file_name().map_or_else(
        || dir.display().to_string(),
        |n| n.to_string_lossy().into_owned(),
    )
}

/// Imports every region folder in `library` that adds something. `progress`
/// receives the index of the folder being imported, the folder count and the
/// region ID. Stops with an error only when cancelled or the library cannot be
/// read; a folder that fails is reported and the rest continue.
pub fn import_library(
    store: &PackageStore,
    library: &Path,
    cancelled: &AtomicBool,
    mut progress: impl FnMut(usize, usize, &str),
) -> Result<LibraryImport, PackageError> {
    let library = fs::canonicalize(library)?;
    fs::create_dir_all(store.root())?;
    let root = fs::canonicalize(store.root())?;
    if library.starts_with(&root) || root.starts_with(&library) {
        return Err(PackageError::Invalid(
            "Choose a library folder outside the installed map folder.".into(),
        ));
    }
    let (found, failed) = candidates(&library)?;
    if found.is_empty() && failed.is_empty() {
        return Err(PackageError::Invalid(
            "This folder has no prepared maps. Select the extracted Offline Maps Library folder."
                .into(),
        ));
    }
    let mut result = LibraryImport {
        failed,
        ..LibraryImport::default()
    };
    let total = found.len();
    for (index, candidate) in found.iter().enumerate() {
        check_cancelled(cancelled)?;
        let region = candidate.manifest.region.as_str();
        progress(index, total, region);
        let installed = store.region_dir(region).join("active.json").exists();
        if candidate.layers_only {
            if !installed {
                result.needs_map.push(region.to_owned());
                continue;
            }
            let present = candidate
                .manifest
                .files
                .keys()
                .any(|name| name.ends_with("/active.json") && store.root().join(name).exists());
            if present {
                result.skipped += 1;
                continue;
            }
        } else if installed {
            result.skipped += 1;
            continue;
        }
        match import_one(store, candidate, cancelled) {
            Ok(()) if candidate.layers_only => result.layers_added.push(region.to_owned()),
            Ok(()) => result.added.push(region.to_owned()),
            Err(e) => {
                check_cancelled(cancelled)?;
                result
                    .failed
                    .push((folder_name(&candidate.dir), e.to_string()));
            }
        }
    }
    progress(total, total, "");
    Ok(result)
}

fn import_one(
    store: &PackageStore,
    candidate: &Candidate,
    cancelled: &AtomicBool,
) -> Result<(), PackageError> {
    let staging = Staging::create(store)?;
    let mut buffer = vec![0; 1024 * 1024];
    fs::copy(candidate.dir.join(MANIFEST), staging.0.join(MANIFEST))?;
    for (name, record) in &candidate.manifest.files {
        let source = regular(&candidate.dir, name)?;
        let target = staging.0.join(name);
        fs::create_dir_all(target.parent().expect("transfer file parent"))?;
        let copied = copy(&source, &target, record.bytes, &mut buffer, cancelled)?;
        if copied != record.bytes {
            return Err(PackageError::Invalid(format!(
                "{name} has an unexpected size."
            )));
        }
    }
    transfer_update::install(store, &staging.0, false, cancelled)?;
    // The installed files were moved out; recovery confirms the commit.
    transfer_update::recover(store)?;
    Ok(())
}

fn copy(
    source: &Path,
    target: &Path,
    limit: u64,
    buffer: &mut [u8],
    cancelled: &AtomicBool,
) -> io::Result<u64> {
    let mut input = File::open(source)?;
    let mut output = File::create_new(target)?;
    let mut copied = 0u64;
    loop {
        check_cancelled(cancelled)?;
        let read = input.read(buffer)?;
        if read == 0 {
            output.sync_all()?;
            return Ok(copied);
        }
        copied += read as u64;
        if copied > limit {
            return Ok(copied);
        }
        output.write_all(&buffer[..read])?;
    }
}

fn check_cancelled(cancelled: &AtomicBool) -> io::Result<()> {
    if cancelled.load(Ordering::Relaxed) {
        return Err(io::Error::new(
            io::ErrorKind::Interrupted,
            "Map import cancelled.",
        ));
    }
    Ok(())
}

/// A private folder on the store's volume, so installing is a rename.
/// Removed with whatever is left in it.
struct Staging(PathBuf);

impl Staging {
    fn create(store: &PackageStore) -> io::Result<Self> {
        let parent = store.root().join(".imports");
        fs::create_dir_all(&parent)?;
        loop {
            let name = format!(
                "{}-{}-{}",
                std::process::id(),
                crate::time::utc_now().replace(':', "-"),
                NEXT.fetch_add(1, Ordering::Relaxed)
            );
            match fs::create_dir(parent.join(&name)) {
                Ok(()) => return Ok(Self(parent.join(name))),
                Err(e) if e.kind() == io::ErrorKind::AlreadyExists => continue,
                Err(e) => return Err(e),
            }
        }
    }
}

impl Drop for Staging {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
        if let Some(parent) = self.0.parent() {
            let _ = fs::remove_dir(parent);
        }
    }
}

#[cfg(test)]
#[path = "library_import_tests.rs"]
mod tests;
