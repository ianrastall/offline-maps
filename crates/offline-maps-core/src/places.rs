//! Saved places: user-created data, stored apart from map packages.
//!
//! The file lives at the store root (`saved-places.json`), outside every
//! region and version directory, so package updates, pruning and region
//! removal never touch it. Each change is written atomically. A file that
//! cannot be read is reported and left untouched; it is never replaced
//! without the caller deciding to move it aside.
use std::fs;
use std::io;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::Coordinates;
use crate::mbtiles::Bounds;
use crate::store::write_atomic;

pub const PLACES_FILE: &str = "saved-places.json";
pub const PLACES_SCHEMA: u32 = 1;
pub const MAX_PLACES: usize = 10_000;
pub const MAX_NAME_CHARS: usize = 120;
pub const MAX_NOTE_CHARS: usize = 2_000;
/// Bound portable backups before parsing them, including on phones.
pub const MAX_BACKUP_BYTES: usize = 16 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub struct PlaceImport {
    pub added: usize,
    pub skipped: usize,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SavedPlace {
    pub id: u64,
    pub name: String,
    pub note: String,
    pub latitude: f64,
    pub longitude: f64,
    pub created_at: String,
    pub updated_at: String,
}

impl SavedPlace {
    pub fn coordinates(&self) -> Option<Coordinates> {
        Coordinates::new(self.latitude, self.longitude)
    }

    /// Whether the map package covering `bounds` shows this place.
    pub fn is_covered_by(&self, bounds: &Bounds) -> bool {
        (bounds.west..=bounds.east).contains(&self.longitude)
            && (bounds.south..=bounds.north).contains(&self.latitude)
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
struct PlacesFile {
    schema: u32,
    next_id: u64,
    places: Vec<SavedPlace>,
}

impl Default for PlacesFile {
    fn default() -> Self {
        Self {
            schema: PLACES_SCHEMA,
            next_id: 1,
            places: Vec::new(),
        }
    }
}

#[derive(Debug)]
pub enum PlacesError {
    Io(io::Error),
    /// The file exists but cannot be read as saved places. It was not changed.
    Unreadable(String),
    Invalid(String),
    NotFound(u64),
    Full,
}

impl std::fmt::Display for PlacesError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Io(e) => write!(f, "Saved places could not be written: {e}"),
            Self::Unreadable(e) => write!(
                f,
                "Saved places file cannot be read ({e}). It has not been changed."
            ),
            Self::Invalid(e) => write!(f, "{e}"),
            Self::NotFound(id) => write!(f, "Saved place {id} no longer exists."),
            Self::Full => write!(f, "No more than {MAX_PLACES} places can be saved."),
        }
    }
}

impl std::error::Error for PlacesError {}

impl From<io::Error> for PlacesError {
    fn from(error: io::Error) -> Self {
        Self::Io(error)
    }
}

pub struct SavedPlaces {
    path: PathBuf,
    data: PlacesFile,
}

fn clean_name(name: &str) -> Result<String, PlacesError> {
    let name = name.trim();
    if name.is_empty() {
        return Err(PlacesError::Invalid("A saved place needs a name.".into()));
    }
    if name.chars().count() > MAX_NAME_CHARS {
        return Err(PlacesError::Invalid(format!(
            "Names are limited to {MAX_NAME_CHARS} characters."
        )));
    }
    if name.chars().any(char::is_control) {
        return Err(PlacesError::Invalid(
            "Names cannot contain control characters.".into(),
        ));
    }
    Ok(name.to_owned())
}

fn clean_note(note: &str) -> Result<String, PlacesError> {
    let note = note.trim();
    if note.chars().count() > MAX_NOTE_CHARS {
        return Err(PlacesError::Invalid(format!(
            "Notes are limited to {MAX_NOTE_CHARS} characters."
        )));
    }
    Ok(note.to_owned())
}

impl SavedPlaces {
    /// Opens `<store root>/saved-places.json`. A missing file is an empty list.
    pub fn open(store_root: &Path) -> Result<Self, PlacesError> {
        let path = store_root.join(PLACES_FILE);
        let data = match fs::read_to_string(&path) {
            Ok(text) => {
                let data: PlacesFile = serde_json::from_str(&text)
                    .map_err(|e| PlacesError::Unreadable(e.to_string()))?;
                if data.schema != PLACES_SCHEMA {
                    return Err(PlacesError::Unreadable(format!(
                        "schema {} is not supported",
                        data.schema
                    )));
                }
                if data
                    .places
                    .iter()
                    .any(|p| p.coordinates().is_none() || p.id >= data.next_id)
                {
                    return Err(PlacesError::Unreadable("contains invalid entries".into()));
                }
                data
            }
            Err(error) if error.kind() == io::ErrorKind::NotFound => PlacesFile::default(),
            Err(error) => return Err(error.into()),
        };
        Ok(Self { path, data })
    }

    /// Renames an unreadable file aside (`saved-places.unreadable-<time>.json`)
    /// so a new list can be started without losing it. Returns the new path.
    pub fn move_aside_unreadable(store_root: &Path) -> io::Result<PathBuf> {
        let stamp: String = crate::time::utc_now()
            .chars()
            .filter(char::is_ascii_alphanumeric)
            .collect();
        let target = store_root.join(format!("saved-places.unreadable-{stamp}.json"));
        fs::rename(store_root.join(PLACES_FILE), &target)?;
        Ok(target)
    }

    pub fn list(&self) -> &[SavedPlace] {
        &self.data.places
    }

    pub fn get(&self, id: u64) -> Option<&SavedPlace> {
        self.data.places.iter().find(|p| p.id == id)
    }

    /// Case-insensitive match on name or note.
    pub fn find(&self, query: &str) -> Vec<&SavedPlace> {
        let query = query.trim().to_lowercase();
        self.data
            .places
            .iter()
            .filter(|p| {
                !query.is_empty()
                    && (p.name.to_lowercase().contains(&query)
                        || p.note.to_lowercase().contains(&query))
            })
            .collect()
    }

    pub fn add(
        &mut self,
        name: &str,
        note: &str,
        at: Coordinates,
    ) -> Result<SavedPlace, PlacesError> {
        if self.data.places.len() >= MAX_PLACES {
            return Err(PlacesError::Full);
        }
        let now = crate::time::utc_now();
        let place = SavedPlace {
            id: self.data.next_id,
            name: clean_name(name)?,
            note: clean_note(note)?,
            latitude: at.latitude(),
            longitude: at.longitude(),
            created_at: now.clone(),
            updated_at: now,
        };
        let mut next = self.data.clone();
        next.next_id += 1;
        next.places.push(place.clone());
        self.commit(next)?;
        Ok(place)
    }

    pub fn update(&mut self, id: u64, name: &str, note: &str) -> Result<(), PlacesError> {
        let (name, note) = (clean_name(name)?, clean_note(note)?);
        let mut next = self.data.clone();
        let place = next
            .places
            .iter_mut()
            .find(|p| p.id == id)
            .ok_or(PlacesError::NotFound(id))?;
        place.name = name;
        place.note = note;
        place.updated_at = crate::time::utc_now();
        self.commit(next)
    }

    pub fn remove(&mut self, id: u64) -> Result<(), PlacesError> {
        let mut next = self.data.clone();
        let before = next.places.len();
        next.places.retain(|p| p.id != id);
        if next.places.len() == before {
            return Err(PlacesError::NotFound(id));
        }
        self.commit(next)
    }

    /// Portable backup, also readable as the desktop's saved-places.json.
    pub fn export_json(&self) -> Result<String, PlacesError> {
        let text = serde_json::to_string_pretty(&self.data).map_err(io::Error::other)?;
        if text.len() > MAX_BACKUP_BYTES {
            return Err(PlacesError::Invalid(
                "Saved-place backups are limited to 16 MB.".into(),
            ));
        }
        Ok(text)
    }

    /// Validate and count a merge without changing memory or disk.
    pub fn preview_import(&self, text: &str) -> Result<PlaceImport, PlacesError> {
        self.prepare_import(text).map(|(_, result)| result)
    }

    /// Merge in one atomic write. Incoming ids never replace local ids; exact
    /// name/note/coordinate duplicates are skipped, including within the file.
    pub fn import_json(&mut self, text: &str) -> Result<PlaceImport, PlacesError> {
        let (next, result) = self.prepare_import(text)?;
        if result.added != 0 {
            self.commit(next)?;
        }
        Ok(result)
    }

    fn prepare_import(&self, text: &str) -> Result<(PlacesFile, PlaceImport), PlacesError> {
        use std::collections::HashSet;
        let invalid = |message: &str| PlacesError::Invalid(message.into());
        if text.len() > MAX_BACKUP_BYTES {
            return Err(invalid("Saved-place backups are limited to 16 MB."));
        }
        let incoming: PlacesFile = serde_json::from_str(text)
            .map_err(|_| invalid("This is not a valid Offline Maps saved-place backup."))?;
        if incoming.schema != PLACES_SCHEMA {
            return Err(invalid("This saved-place backup version is not supported."));
        }
        if incoming.places.len() > MAX_PLACES {
            return Err(PlacesError::Full);
        }
        if incoming.next_id == 0 {
            return Err(invalid(
                "The backup contains invalid saved-place identifiers.",
            ));
        }
        let key = |p: &SavedPlace| {
            // Positive and negative zero represent the same coordinate.
            let bits = |n: f64| if n == 0.0 { 0 } else { n.to_bits() };
            (
                p.name.clone(),
                p.note.clone(),
                bits(p.latitude),
                bits(p.longitude),
            )
        };
        let mut keys: HashSet<_> = self.data.places.iter().map(key).collect();
        let mut ids = HashSet::new();
        let mut next = self.data.clone();
        let mut result = PlaceImport {
            added: 0,
            skipped: 0,
        };
        for mut place in incoming.places {
            if place.id == 0 || place.id >= incoming.next_id || !ids.insert(place.id) {
                return Err(invalid(
                    "The backup contains invalid saved-place identifiers.",
                ));
            }
            if place.coordinates().is_none() {
                return Err(invalid(
                    "The backup contains coordinates outside the valid latitude or longitude range.",
                ));
            }
            place.name = clean_name(&place.name)?;
            place.note = clean_note(&place.note)?;
            if [&place.created_at, &place.updated_at]
                .iter()
                .any(|s| s.len() > 64 || s.chars().any(char::is_control))
            {
                return Err(invalid(
                    "The backup contains an invalid saved-place timestamp.",
                ));
            }
            if !keys.insert(key(&place)) {
                result.skipped += 1;
                continue;
            }
            if next.places.len() >= MAX_PLACES {
                return Err(PlacesError::Full);
            }
            place.id = next.next_id;
            next.next_id = next
                .next_id
                .checked_add(1)
                .ok_or_else(|| invalid("No more saved-place identifiers are available."))?;
            next.places.push(place);
            result.added += 1;
        }
        Ok((next, result))
    }

    /// Writes `next` to disk, then adopts it; a failed write leaves both the
    /// file and this list as they were.
    fn commit(&mut self, next: PlacesFile) -> Result<(), PlacesError> {
        if let Some(parent) = self.path.parent() {
            fs::create_dir_all(parent)?;
        }
        let json = serde_json::to_vec_pretty(&next).map_err(io::Error::other)?;
        write_atomic(&self.path, &json)?;
        self.data = next;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::tests::TempDir;

    fn at(lat: f64, lon: f64) -> Coordinates {
        Coordinates::new(lat, lon).unwrap()
    }

    #[test]
    fn add_update_remove_and_persist() {
        let dir = TempDir::new();
        let mut places = SavedPlaces::open(&dir.0).unwrap();
        assert!(places.list().is_empty());
        let home = places
            .add("  Home ", "Back door key with neighbour", at(40.15, -88.96))
            .unwrap();
        let shelter = places.add("Shelter", "", at(37.73, -89.22)).unwrap();
        assert_eq!((home.id, shelter.id, home.name.as_str()), (1, 2, "Home"));

        places
            .update(shelter.id, "County shelter", "Bring ID")
            .unwrap();
        places.remove(home.id).unwrap();
        assert!(matches!(
            places.remove(home.id),
            Err(PlacesError::NotFound(1))
        ));

        let reopened = SavedPlaces::open(&dir.0).unwrap();
        assert_eq!(reopened.list().len(), 1);
        assert_eq!(reopened.get(2).unwrap().name, "County shelter");
        assert_eq!(reopened.find("id").len(), 1);
        assert!(reopened.find("").is_empty());
        // Ids are not reused after removal.
        let mut reopened = reopened;
        assert_eq!(reopened.add("Work", "", at(41.0, -88.0)).unwrap().id, 3);
        assert!(!dir.0.join("saved-places.json.tmp").exists());
    }

    #[test]
    fn validates_input() {
        let dir = TempDir::new();
        let mut places = SavedPlaces::open(&dir.0).unwrap();
        for name in ["", "   ", "tab\there", &"x".repeat(121)] {
            assert!(
                matches!(
                    places.add(name, "", at(40.0, -89.0)),
                    Err(PlacesError::Invalid(_))
                ),
                "{name:?}"
            );
        }
        assert!(
            places
                .add("Ok", &"n".repeat(2001), at(40.0, -89.0))
                .is_err()
        );
        assert!(places.list().is_empty());
        assert!(!dir.0.join(PLACES_FILE).exists());
    }

    #[test]
    fn unreadable_file_is_reported_and_preserved() {
        let dir = TempDir::new();
        fs::write(dir.0.join(PLACES_FILE), b"{ not json").unwrap();
        assert!(matches!(
            SavedPlaces::open(&dir.0),
            Err(PlacesError::Unreadable(_))
        ));
        assert_eq!(fs::read(dir.0.join(PLACES_FILE)).unwrap(), b"{ not json");

        let aside = SavedPlaces::move_aside_unreadable(&dir.0).unwrap();
        assert_eq!(fs::read(&aside).unwrap(), b"{ not json");
        assert!(SavedPlaces::open(&dir.0).unwrap().list().is_empty());

        fs::write(
            dir.0.join(PLACES_FILE),
            br#"{"schema":1,"next_id":1,"places":[{"id":5,"name":"x","note":"","latitude":91,"longitude":0,"created_at":"","updated_at":""}]}"#,
        )
        .unwrap();
        assert!(matches!(
            SavedPlaces::open(&dir.0),
            Err(PlacesError::Unreadable(_))
        ));
    }

    #[test]
    fn coverage_against_package_bounds() {
        let dir = TempDir::new();
        let mut places = SavedPlaces::open(&dir.0).unwrap();
        let illinois = Bounds::new(-91.51608, 36.96731, -87.49264, 42.50941).unwrap();
        let inside = places.add("Clinton", "", at(40.15, -88.96)).unwrap();
        let outside = places.add("Indianapolis", "", at(39.77, -86.16)).unwrap();
        assert!(inside.is_covered_by(&illinois));
        assert!(!outside.is_covered_by(&illinois));
    }

    #[test]
    fn backup_merge_is_atomic_idempotent_and_preserves_local_places() {
        let source_dir = TempDir::new();
        let mut source = SavedPlaces::open(&source_dir.0).unwrap();
        source
            .add(
                "Shelter",
                "Bring water\nAccessible entrance",
                at(38.9, -77.0),
            )
            .unwrap();
        source.add("家", "Meeting point", at(40.0, -89.0)).unwrap();
        let backup = source.export_json().unwrap();
        let target_dir = TempDir::new();
        let mut target = SavedPlaces::open(&target_dir.0).unwrap();
        let local = target.add("Home", "Keep this", at(41.0, -88.0)).unwrap();
        let before = fs::read(target_dir.0.join(PLACES_FILE)).unwrap();
        assert_eq!(
            target.preview_import(&backup).unwrap(),
            PlaceImport {
                added: 2,
                skipped: 0
            }
        );
        assert_eq!(fs::read(target_dir.0.join(PLACES_FILE)).unwrap(), before);
        assert_eq!(target.list(), std::slice::from_ref(&local));
        assert_eq!(target.import_json(&backup).unwrap().added, 2);
        assert_eq!(target.get(local.id), Some(&local));
        assert_eq!(target.list()[1].id, 2);
        assert_eq!(target.list()[1].created_at, source.list()[0].created_at);
        let merged = fs::read(target_dir.0.join(PLACES_FILE)).unwrap();
        assert_eq!(
            target.import_json(&backup).unwrap(),
            PlaceImport {
                added: 0,
                skipped: 2
            }
        );
        assert_eq!(fs::read(target_dir.0.join(PLACES_FILE)).unwrap(), merged);
        assert_eq!(
            SavedPlaces::open(&target_dir.0).unwrap().list(),
            target.list()
        );
        // Same destination with a changed note is kept as a separate entry.
        source.update(1, "Shelter", "Different plan").unwrap();
        assert_eq!(
            target.import_json(&source.export_json().unwrap()).unwrap(),
            PlaceImport {
                added: 1,
                skipped: 1
            }
        );
    }

    #[test]
    fn invalid_backup_never_partially_merges() {
        let dir = TempDir::new();
        let mut places = SavedPlaces::open(&dir.0).unwrap();
        places.add("Existing", "", at(40.0, -89.0)).unwrap();
        let before = fs::read(dir.0.join(PLACES_FILE)).unwrap();
        let mut good: serde_json::Value =
            serde_json::from_str(&places.export_json().unwrap()).unwrap();
        good["places"][0]["name"] = "New destination".into();
        let mut bad_cases = vec!["not json".to_owned()];
        for (field, value) in [
            ("latitude", serde_json::json!(91)),
            ("longitude", serde_json::json!(-181)),
            ("name", serde_json::json!("\t")),
            ("note", serde_json::json!("x".repeat(2001))),
            ("id", serde_json::json!(0)),
            ("updated_at", serde_json::json!("bad\ntimestamp")),
        ] {
            let mut bad = good.clone();
            let mut second = bad["places"][0].clone();
            second["id"] = 2.into();
            second[field] = value;
            bad["places"].as_array_mut().unwrap().push(second);
            bad["next_id"] = 3.into();
            bad_cases.push(bad.to_string());
        }
        let mut unsupported = good.clone();
        unsupported["schema"] = 99.into();
        bad_cases.push(unsupported.to_string());
        let mut duplicate_id = good.clone();
        duplicate_id["places"]
            .as_array_mut()
            .unwrap()
            .push(good["places"][0].clone());
        bad_cases.push(duplicate_id.to_string());
        for bad in bad_cases {
            assert!(places.preview_import(&bad).is_err());
            assert!(places.import_json(&bad).is_err());
            assert_eq!(fs::read(dir.0.join(PLACES_FILE)).unwrap(), before);
            assert_eq!(places.list().len(), 1);
        }
    }

    #[test]
    fn duplicate_entries_in_backup_and_capacity_are_checked() {
        let dir = TempDir::new();
        let mut places = SavedPlaces::open(&dir.0).unwrap();
        places.add("Point", "", at(0.0, 0.0)).unwrap();
        let mut backup: serde_json::Value =
            serde_json::from_str(&places.export_json().unwrap()).unwrap();
        backup["next_id"] = 3.into();
        let mut second = backup["places"][0].clone();
        second["id"] = 2.into();
        second["latitude"] = serde_json::json!(-0.0);
        backup["places"].as_array_mut().unwrap().push(second);
        let other = TempDir::new();
        let mut target = SavedPlaces::open(&other.0).unwrap();
        assert_eq!(
            target.import_json(&backup.to_string()).unwrap(),
            PlaceImport {
                added: 1,
                skipped: 1
            }
        );
        let mut full = places.data.clone();
        full.places = (1..=MAX_PLACES)
            .map(|id| {
                let mut p = places.list()[0].clone();
                p.id = id as u64;
                p.name = format!("Point {id}");
                p
            })
            .collect();
        full.next_id = MAX_PLACES as u64 + 1;
        places.commit(full).unwrap();
        let before = fs::read(dir.0.join(PLACES_FILE)).unwrap();
        assert!(matches!(
            places.import_json(&target.export_json().unwrap()),
            Err(PlacesError::Full)
        ));
        assert_eq!(fs::read(dir.0.join(PLACES_FILE)).unwrap(), before);
        assert!(
            target
                .import_json(&" ".repeat(MAX_BACKUP_BYTES + 1))
                .is_err()
        );
    }

    #[test]
    fn backup_write_failure_does_not_change_in_memory_places() {
        let source = TempDir::new();
        let mut incoming = SavedPlaces::open(&source.0).unwrap();
        incoming.add("New", "", at(1.0, 2.0)).unwrap();
        let dir = TempDir::new();
        let mut target = SavedPlaces::open(&dir.0).unwrap();
        // A directory at the file path makes atomic replacement fail on both OSes.
        fs::create_dir(dir.0.join(PLACES_FILE)).unwrap();
        assert!(
            target
                .import_json(&incoming.export_json().unwrap())
                .is_err()
        );
        assert!(target.list().is_empty());
        assert!(dir.0.join(PLACES_FILE).is_dir());
    }
}
