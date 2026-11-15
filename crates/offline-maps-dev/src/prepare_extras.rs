//! `prepare-extras`: builds the prepared stations-and-addresses files that
//! are published with the project's releases, so installed copies need not
//! each download and read a raw extract.
//!
//! For each chosen catalog region the raw extract is downloaded (or, with
//! `--local <folder>`, taken from that folder when it is there) and read by
//! the core exactly as the app does it, in a working store under the output
//! folder; the resulting extras database is copied out with its record stored
//! inside (`extras::stamp_prepared`) as `<region>-extras.sqlite`, beside a
//! `<region>-extras.json` with its size, SHA-256 and data time. The working
//! copy is then removed. A region whose two files exist is skipped, so a run
//! can be stopped and continued. `manifest.json` is rewritten from every
//! `*-extras.json` in the folder at the end of each run; `tools/bundles/publish.py`
//! uploads the files and then the manifest.
use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicBool;
use std::time::Instant;

use offline_maps_core::bundles::{
    MANIFEST_FILE, MANIFEST_SCHEMA, Manifest, PreparedExtras, extras_file_name,
};
use offline_maps_core::download::{
    Fetched, HttpTransport, Progress, RemoteInfo, Transport, UpdateOptions,
};
use offline_maps_core::extras::{
    EXTRACT_VERSION, EXTRAS_SCHEMA, ExtrasOutcome, stamp_prepared, update_extras, verify_prepared,
};
use offline_maps_core::package::{Region, sha256_file};
use offline_maps_core::regions;
use offline_maps_core::store::PackageStore;

fn text(e: impl std::fmt::Display) -> String {
    e.to_string()
}

/// Reads a raw extract from a folder of files already downloaded (`--local`)
/// when one with the address's file name is there, and from the network
/// otherwise.
struct LocalFirst {
    folder: Option<PathBuf>,
    http: HttpTransport,
}

impl LocalFirst {
    fn local(&self, url: &str) -> Option<PathBuf> {
        let name = url.rsplit('/').next()?;
        self.folder
            .as_ref()
            .map(|folder| folder.join(name))
            .filter(|path| path.is_file())
    }

    fn info(path: &Path) -> std::io::Result<RemoteInfo> {
        Ok(RemoteInfo {
            length: Some(std::fs::metadata(path)?.len()),
            etag: None,
            last_modified: None,
            accepts_ranges: false,
        })
    }
}

impl Transport for LocalFirst {
    fn probe(&self, url: &str) -> std::io::Result<RemoteInfo> {
        match self.local(url) {
            Some(path) => Self::info(&path),
            None => self.http.probe(url),
        }
    }

    fn fetch(&self, url: &str, resume: Option<(u64, &str)>) -> std::io::Result<Fetched> {
        match self.local(url) {
            Some(path) => Ok(Fetched::Full {
                info: Self::info(&path)?,
                body: Box::new(std::fs::File::open(path)?),
            }),
            None => self.http.fetch(url, resume),
        }
    }
}

/// Builds one region's prepared file in `out`; returns its manifest entry.
fn prepare(
    region: &Region,
    out: &Path,
    store: &PackageStore,
    transport: &LocalFirst,
) -> Result<PreparedExtras, String> {
    let cancelled = AtomicBool::new(false);
    let mut last = None;
    let mut progress = |p: Progress| {
        if let Progress::Downloading {
            received,
            total: Some(total),
        } = p
        {
            let step = received * 4 / total.max(1);
            if last != Some(step) {
                last = Some(step);
                print!(" {}%", step * 25);
                let _ = std::io::Write::flush(&mut std::io::stdout());
            }
        }
    };
    let active = match update_extras(
        store,
        region,
        transport,
        &UpdateOptions::default(),
        &mut progress,
        &cancelled,
    )
    .map_err(text)?
    {
        ExtrasOutcome::Activated { extras, .. } | ExtrasOutcome::UpToDate(extras) => extras,
        ExtrasOutcome::Available(_) => return Err("nothing was downloaded".into()),
    };
    let name = extras_file_name(region.id);
    let staged = out.join(format!("{name}.part"));
    std::fs::copy(&active.path, &staged).map_err(text)?;
    stamp_prepared(&staged, &active.record).map_err(text)?;
    // What an installed copy will check, checked here first.
    verify_prepared(&staged, region).map_err(text)?;
    let (sha256, bytes) = sha256_file(&staged, &cancelled).map_err(text)?;
    std::fs::rename(&staged, out.join(&name)).map_err(text)?;
    let entry = PreparedExtras {
        file: name,
        bytes,
        sha256,
        data_timestamp: active.record.data_timestamp.clone(),
        extras_schema: EXTRAS_SCHEMA,
        extract_version: EXTRACT_VERSION,
    };
    std::fs::write(
        out.join(format!("{}-extras.json", region.id)),
        serde_json::to_vec_pretty(&entry).map_err(text)?,
    )
    .map_err(text)?;
    // The working copy (and anything left in staging) is no longer needed.
    let _ = std::fs::remove_dir_all(store.region_dir(region.id));
    Ok(entry)
}

/// Writes `manifest.json` from every entry file in `out`.
fn write_manifest(out: &Path) -> Result<usize, String> {
    let mut extras = BTreeMap::new();
    for entry in std::fs::read_dir(out).map_err(text)? {
        let path = entry.map_err(text)?.path();
        let name = path
            .file_name()
            .unwrap_or_default()
            .to_string_lossy()
            .into_owned();
        let Some(id) = name.strip_suffix("-extras.json") else {
            continue;
        };
        if regions::find(id).is_none() {
            return Err(format!("{name}: no region {id}"));
        }
        let listed: PreparedExtras = serde_json::from_slice(&std::fs::read(&path).map_err(text)?)
            .map_err(|e| format!("{name}: {e}"))?;
        let size = std::fs::metadata(out.join(&listed.file))
            .map_err(|e| format!("{}: {e}", listed.file))?
            .len();
        if size != listed.bytes {
            return Err(format!(
                "{} is not the file its entry describes",
                listed.file
            ));
        }
        extras.insert(id.to_owned(), listed);
    }
    let manifest = Manifest {
        schema: MANIFEST_SCHEMA,
        generated_at: offline_maps_core::time::utc_now(),
        extras,
    };
    std::fs::write(
        out.join(MANIFEST_FILE),
        serde_json::to_vec_pretty(&manifest).map_err(text)?,
    )
    .map_err(text)?;
    Ok(manifest.extras.len())
}

pub fn run(out: &Path, options: &HashMap<String, String>) -> Result<(), String> {
    let wanted: Option<Vec<&str>> = options.get("regions").map(|r| r.split(',').collect());
    if let Some(ids) = &wanted
        && let Some(unknown) = ids.iter().find(|id| regions::find(id).is_none())
    {
        return Err(format!("no region {unknown}"));
    }
    let chosen: Vec<&Region> = regions::ALL
        .iter()
        .filter(|r| wanted.as_ref().is_none_or(|ids| ids.contains(&r.id)))
        .filter(|r| options.get("continent").is_none_or(|c| c == r.continent))
        .filter(|r| options.get("country").is_none_or(|c| c == r.country))
        .collect();
    if chosen.is_empty() {
        return Err("no regions chosen".into());
    }
    std::fs::create_dir_all(out).map_err(text)?;
    let store = PackageStore::new(out.join("work"));
    let transport = LocalFirst {
        folder: options.get("local").map(PathBuf::from),
        http: HttpTransport::new(),
    };
    let started = Instant::now();
    let (mut built, mut skipped, mut failed) = (0, 0, Vec::new());
    for (i, region) in chosen.iter().enumerate() {
        let done = out.join(extras_file_name(region.id)).is_file()
            && out.join(format!("{}-extras.json", region.id)).is_file();
        if done {
            skipped += 1;
            continue;
        }
        print!("[{}/{}] {}:", i + 1, chosen.len(), region.id);
        let _ = std::io::Write::flush(&mut std::io::stdout());
        let began = Instant::now();
        if transport.local(region.extras_url).is_some() {
            print!(" (local file)");
        }
        match prepare(region, out, &store, &transport) {
            Ok(entry) => {
                built += 1;
                println!(
                    " {:.1} MB from {:.0} MB, data {}, {:.0} s",
                    entry.bytes as f64 / 1e6,
                    region.extras_bytes as f64 / 1e6,
                    entry.data_timestamp.as_deref().unwrap_or("undated"),
                    began.elapsed().as_secs_f64()
                );
            }
            Err(e) => {
                println!(" FAILED: {e}");
                failed.push(region.id);
            }
        }
    }
    let listed = write_manifest(out)?;
    println!(
        "{built} built, {skipped} already there, {} failed in {:.0} s; manifest lists {listed}",
        failed.len(),
        started.elapsed().as_secs_f64()
    );
    if failed.is_empty() {
        Ok(())
    } else {
        Err(format!("failed: {}", failed.join(", ")))
    }
}
