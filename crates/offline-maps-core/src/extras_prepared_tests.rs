//! Prepared extras: published files checked against their listing and their
//! own contents.
use super::*;
use crate::bundles::{MANIFEST_FILE, MANIFEST_SCHEMA, Manifest, PreparedExtras};
use crate::bundles::{extras_file_name, file_url};
use crate::download::{Fetched, RemoteInfo};

const BASE: &str = "https://example.org/extras";

/// Serves files by address, as a release's file list does.
#[derive(Default)]
struct Files(std::cell::RefCell<HashMap<String, Vec<u8>>>);

fn not_found() -> io::Error {
    io::Error::new(io::ErrorKind::NotFound, "Server returned HTTP 404")
}

impl Files {
    fn put(&self, url: &str, bytes: Vec<u8>) {
        self.0.borrow_mut().insert(url.to_owned(), bytes);
    }

    fn info(bytes: &[u8]) -> RemoteInfo {
        RemoteInfo {
            length: Some(bytes.len() as u64),
            etag: Some(format!("\"{}\"", bytes.len())),
            last_modified: Some("Sun, 15 Nov 2026 02:00:00 GMT".into()),
            accepts_ranges: true,
        }
    }
}

impl Transport for Files {
    fn probe(&self, url: &str) -> io::Result<RemoteInfo> {
        self.0
            .borrow()
            .get(url)
            .map(|bytes| Self::info(bytes))
            .ok_or_else(not_found)
    }

    fn fetch(&self, url: &str, _: Option<(u64, &str)>) -> io::Result<Fetched> {
        let files = self.0.borrow();
        let bytes = files.get(url).ok_or_else(not_found)?;
        Ok(Fetched::Full {
            info: Self::info(bytes),
            body: Box::new(io::Cursor::new(bytes.clone())),
        })
    }
}

#[test]
fn prepared_extras_are_checked_and_installed() {
    let cancelled = AtomicBool::new(false);

    // The maintainer's side: build from the raw extract, then store the
    // record in the file.
    let dir = TempDir::new();
    let builder = PackageStore::new(dir.0.join("builder"));
    let raw = FakeTransport::new(sample(true, true), "\"x1\"");
    let ExtrasOutcome::Activated { extras: built, .. } = update_extras(
        &builder,
        &ILLINOIS,
        &raw,
        &options(),
        &mut |_| {},
        &cancelled,
    )
    .unwrap() else {
        panic!("expected activation");
    };
    assert!(built.record.bbox.is_some() && built.record.prepared.is_none());
    let published = dir.0.join("us-il-extras.sqlite");
    let unstamped = dir.0.join("unstamped.sqlite");
    fs::copy(&built.path, &published).unwrap();
    fs::copy(&built.path, &unstamped).unwrap();
    stamp_prepared(&published, &built.record).unwrap();
    let (sha256, length) = sha256_file(&published, &cancelled).unwrap();
    let listed = PreparedExtras {
        file: extras_file_name(ILLINOIS.id),
        bytes: length,
        sha256,
        data_timestamp: Some("2026-11-13T21:21:15Z".into()),
        extras_schema: EXTRAS_SCHEMA,
        extract_version: EXTRACT_VERSION,
    };
    let url = file_url(BASE, &listed.file);
    let server = Files::default();
    server.put(&url, fs::read(&published).unwrap());

    // The manifest is read from beside the files.
    let manifest = Manifest {
        schema: MANIFEST_SCHEMA,
        generated_at: "2026-11-15T02:00:00Z".into(),
        extras: [(ILLINOIS.id.to_owned(), listed.clone())].into(),
    };
    server.put(
        &file_url(BASE, MANIFEST_FILE),
        serde_json::to_vec(&manifest).unwrap(),
    );
    let read = Manifest::fetch(&server, BASE).unwrap();
    assert_eq!(read.extras_for(ILLINOIS.id), Some(&listed));
    assert!(Manifest::fetch(&server, "https://example.org/nowhere").is_err());

    let run = |store: &PackageStore, listed: &PreparedExtras, o: &UpdateOptions| {
        update_extras_prepared(
            store,
            &ILLINOIS,
            BASE,
            listed,
            &server,
            o,
            &mut |_| {},
            &cancelled,
        )
    };
    let store = PackageStore::new(dir.0.join("store"));
    // A check downloads nothing and reports the listed size and date.
    let check = UpdateOptions {
        check_only: true,
        ..options()
    };
    let ExtrasOutcome::Available(newer) = run(&store, &listed, &check).unwrap() else {
        panic!("expected an available file");
    };
    assert_eq!(
        (newer.length, newer.date.as_deref()),
        (Some(length), Some("2026-11-13"))
    );
    assert!(active_extras(&store, ILLINOIS.id).unwrap().is_none());

    let ExtrasOutcome::Activated {
        extras, replaced, ..
    } = run(&store, &listed, &options()).unwrap()
    else {
        panic!("expected activation");
    };
    assert_eq!(replaced, None);
    // The record is the builder's, with this download's own details.
    assert_eq!(extras.record.stats, built.record.stats);
    assert_eq!(extras.record.source, built.record.source);
    assert_eq!(extras.record.sha256, built.record.sha256);
    let source = extras.record.prepared.clone().unwrap();
    assert_eq!(
        (source.url.as_str(), source.sha256.as_str()),
        (url.as_str(), listed.sha256.as_str())
    );
    assert!(stations_geojson(&extras.path).unwrap().contains("Shell"));
    let id = extras_id(ILLINOIS.id);
    assert!(!store.staging_file(&id).exists());
    // The same file again changes nothing.
    assert!(matches!(
        run(&store, &listed, &options()).unwrap(),
        ExtrasOutcome::UpToDate(_)
    ));

    // Extras built here from newer data are kept; from older data, replaced.
    let record_path = builder.version_dir(&id, &built.version).join(RECORD_FILE);
    let with_data_time = |time: &str| {
        let record = ExtrasRecord {
            data_timestamp: Some(time.into()),
            ..built.record.clone()
        };
        fs::write(&record_path, serde_json::to_vec(&record).unwrap()).unwrap();
    };
    with_data_time("2026-11-14T21:21:15Z");
    assert!(matches!(
        run(&builder, &listed, &options()).unwrap(),
        ExtrasOutcome::UpToDate(_)
    ));
    with_data_time("2026-11-10T21:21:15Z");
    let ExtrasOutcome::Activated { replaced, .. } = run(&builder, &listed, &options()).unwrap()
    else {
        panic!("expected replacement");
    };
    assert_eq!(replaced.as_deref(), Some(built.version.as_str()));

    // A file that is not the one listed is refused and nothing is kept.
    let fresh = |name: &str| PackageStore::new(dir.0.join(name));
    let wrong_sum = PreparedExtras {
        sha256: "0".repeat(64),
        ..listed.clone()
    };
    let store = fresh("wrong-sum");
    assert!(matches!(
        run(&store, &wrong_sum, &options()),
        Err(UpdateError::Invalid(_))
    ));
    assert!(!store.staging_file(&id).exists());
    let wrong_size = PreparedExtras {
        bytes: length + 1,
        ..listed.clone()
    };
    assert!(matches!(
        run(&fresh("wrong-size"), &wrong_size, &options()),
        Err(UpdateError::Invalid(_))
    ));
    // So is another region's file, even when listed correctly.
    let other = dir.0.join("other.sqlite");
    fs::copy(&published, &other).unwrap();
    stamp_prepared(
        &other,
        &ExtrasRecord {
            region_id: "us-in".into(),
            ..built.record.clone()
        },
    )
    .unwrap();
    let (other_sha, other_length) = sha256_file(&other, &cancelled).unwrap();
    server.put(&url, fs::read(&other).unwrap());
    let elsewhere = PreparedExtras {
        sha256: other_sha,
        bytes: other_length,
        ..listed.clone()
    };
    let store = fresh("elsewhere");
    assert!(matches!(
        run(&store, &elsewhere, &options()),
        Err(UpdateError::Invalid(_))
    ));
    assert!(active_extras(&store, ILLINOIS.id).unwrap().is_none());
    // An extras file with no record in it is not a prepared file.
    assert!(verify_prepared(&unstamped, &ILLINOIS).is_err());
    assert!(verify_prepared(&published, &ILLINOIS).is_ok());
}
