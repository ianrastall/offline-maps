//! Prepared region files published with this project's releases, so that
//! installed copies do not each download a raw extract from Geofabrik and
//! process it. A manifest beside the files lists each one's size, SHA-256
//! and data date; the app checks a download against it and then against the
//! file's own contents (see `extras::update_extras_prepared`).
//!
//! ```text
//! <base>/manifest.json
//! <base>/us-il-extras.sqlite     stations, addresses and outdoor places
//! ```
//!
//! A region with no usable entry is prepared on the computer as before. The
//! manifest names files, never addresses: every download stays under `base`.
use std::collections::BTreeMap;
use std::io::{self, Read};

use serde::{Deserialize, Serialize};

use crate::download::{Fetched, Transport};
use crate::extras::{EXTRACT_VERSION, EXTRAS_SCHEMA};

/// Where the prepared files are published.
pub const BUNDLES_BASE: &str =
    "https://github.com/ianrastall/offline-maps/releases/download/extras";
pub const MANIFEST_FILE: &str = "manifest.json";
pub const MANIFEST_SCHEMA: u32 = 1;
/// A manifest of every region is about 0.2 MB.
const MANIFEST_MAX_BYTES: u64 = 8 << 20;

/// A region's prepared stations and addresses: an extras database as the app
/// builds it, with its record stored inside (`extras::stamp_prepared`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct PreparedExtras {
    /// File name beside the manifest (`us-il-extras.sqlite`).
    pub file: String,
    pub bytes: u64,
    pub sha256: String,
    /// OSM data time of the raw extract it was made from (RFC 3339), when the
    /// extract recorded one.
    #[serde(default)]
    pub data_timestamp: Option<String>,
    /// The formats it was written in; a copy of the app that writes others
    /// prepares its own.
    pub extras_schema: u32,
    pub extract_version: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Manifest {
    pub schema: u32,
    /// When the manifest was written (RFC 3339).
    pub generated_at: String,
    /// Region ID to its prepared stations and addresses.
    #[serde(default)]
    pub extras: BTreeMap<String, PreparedExtras>,
}

fn invalid(message: impl Into<String>) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, message.into())
}

/// The size of a region's prepared stations and addresses as published when
/// the app was built (`prepared_data.rs`, written by `tools/bundles/publish.py`),
/// for showing before a download; 0 when none was published.
pub fn prepared_extras_bytes(region_id: &str) -> u64 {
    crate::prepared_data::PREPARED_EXTRAS
        .binary_search_by_key(&region_id, |(id, _)| id)
        .map_or(0, |i| crate::prepared_data::PREPARED_EXTRAS[i].1)
}

/// The file name a region's prepared extras are published under.
pub fn extras_file_name(region_id: &str) -> String {
    format!("{region_id}-extras.sqlite")
}

/// The address of `file` under `base`.
pub fn file_url(base: &str, file: &str) -> String {
    format!("{}/{file}", base.trim_end_matches('/'))
}

impl Manifest {
    pub fn parse(bytes: &[u8]) -> io::Result<Self> {
        let manifest: Self = serde_json::from_slice(bytes)
            .map_err(|e| invalid(format!("the list of prepared files is unreadable: {e}")))?;
        if manifest.schema != MANIFEST_SCHEMA {
            return Err(invalid("the list of prepared files is in another format"));
        }
        Ok(manifest)
    }

    /// Reads `<base>/manifest.json`.
    pub fn fetch(transport: &dyn Transport, base: &str) -> io::Result<Self> {
        let body = match transport.fetch(&file_url(base, MANIFEST_FILE), None)? {
            Fetched::Full { body, .. } => body,
            Fetched::Partial { .. } => return Err(invalid("unexpected partial response")),
        };
        let mut bytes = Vec::new();
        body.take(MANIFEST_MAX_BYTES + 1).read_to_end(&mut bytes)?;
        if bytes.len() as u64 > MANIFEST_MAX_BYTES {
            return Err(invalid("the list of prepared files is too large"));
        }
        Self::parse(&bytes)
    }

    /// The region's prepared stations and addresses, when this copy of the
    /// app can use them: written in its formats, under the expected name and
    /// with a well-formed checksum.
    pub fn extras_for(&self, region_id: &str) -> Option<&PreparedExtras> {
        self.extras.get(region_id).filter(|p| {
            p.file == extras_file_name(region_id)
                && p.extras_schema == EXTRAS_SCHEMA
                && p.extract_version == EXTRACT_VERSION
                && p.bytes > 0
                && p.sha256.len() == 64
                && p.sha256
                    .bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(region: &str) -> PreparedExtras {
        PreparedExtras {
            file: extras_file_name(region),
            bytes: 10,
            sha256: "ab".repeat(32),
            data_timestamp: Some("2026-11-13T21:21:15Z".into()),
            extras_schema: EXTRAS_SCHEMA,
            extract_version: EXTRACT_VERSION,
        }
    }

    #[test]
    fn lists_only_files_this_copy_can_use() {
        let mut manifest = Manifest {
            schema: MANIFEST_SCHEMA,
            generated_at: "2026-11-15T00:00:00Z".into(),
            extras: BTreeMap::new(),
        };
        manifest.extras.insert("us-il".into(), entry("us-il"));
        // Another format, a name that is not the region's, a bad checksum.
        manifest.extras.insert(
            "us-in".into(),
            PreparedExtras {
                extract_version: EXTRACT_VERSION + 1,
                ..entry("us-in")
            },
        );
        manifest.extras.insert(
            "us-ia".into(),
            PreparedExtras {
                file: "../elsewhere.sqlite".into(),
                ..entry("us-ia")
            },
        );
        manifest.extras.insert(
            "us-wi".into(),
            PreparedExtras {
                sha256: "XYZ".into(),
                ..entry("us-wi")
            },
        );
        let json = serde_json::to_vec(&manifest).unwrap();
        let read = Manifest::parse(&json).unwrap();
        assert_eq!(read, manifest);
        assert_eq!(read.extras_for("us-il"), Some(&entry("us-il")));
        for id in ["us-in", "us-ia", "us-wi", "us-mo"] {
            assert_eq!(read.extras_for(id), None, "{id}");
        }
        assert_eq!(
            file_url("https://example.org/x/", "us-il-extras.sqlite"),
            "https://example.org/x/us-il-extras.sqlite"
        );
        // Fields added later are ignored; another schema is refused.
        let later = br#"{"schema":1,"generated_at":"t","extras":{},"maps":{"us-il":{}}}"#;
        assert!(Manifest::parse(later).unwrap().extras.is_empty());
        assert!(Manifest::parse(br#"{"schema":2,"generated_at":"t"}"#).is_err());
        assert!(Manifest::parse(b"<html>").is_err());
    }
}
