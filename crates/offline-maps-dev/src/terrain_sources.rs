//! `terrain-sources`: writes the core's table of whose elevation data each
//! catalog region's terrain is made from, and the same sources as a list for
//! the third-party notices.
//!
//! Mapterhorn's coverage index (1.6 MB) is a PMTiles archive whose tiles are
//! JSON objects: source ID to that source's name, producer, licence, website
//! and resolution. The zoom 12 tiles touching a region's outer box give its
//! sources. Parts of one dataset (same short name, producer and licence) are
//! written once.
use std::collections::hash_map::Entry;
use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::fmt::Write as _;

use offline_maps_core::download::{HttpTransport, Transport};
use offline_maps_core::mbtiles::Bounds;
use offline_maps_core::package::Region;
use offline_maps_core::pmtiles::Index;
use offline_maps_core::regions;
use offline_maps_core::terrain::{TERRAIN_MAX_ZOOM, tile_range};
use offline_maps_core::terrain_sources::COVERAGE_INDEX_URL;

/// What identifies one credit: short name, producer, licence.
type Key = (String, String, String);

struct Group {
    website: String,
    /// The finest of its parts, in metres.
    resolution: f64,
}

/// "Mon, 14 Sep 2026 05:51:25 GMT" as "2026-09-14".
fn iso_date(http: &str) -> Option<String> {
    let mut parts = http.split_whitespace().skip(1);
    let (day, month, year) = (parts.next()?, parts.next()?, parts.next()?);
    let month = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ]
    .iter()
    .position(|m| *m == month)?
        + 1;
    Some(format!("{year}-{month:02}-{:02}", day.parse::<u8>().ok()?))
}

/// A box holding the one a region's terrain is downloaded for (its map's
/// bounds): the region's outer box. The parts of regions cut at the 180th
/// meridian have an outer box as wide as the world, because the raw extract
/// they share crosses the meridian; for them it is the outline's box, which
/// the catalog's required points mark at a tenth in from each side, widened
/// by half a degree as outer boxes are.
fn terrain_box(region: &Region) -> Bounds {
    let outer = region.outer;
    if outer.east - outer.west <= 180.0 {
        return outer;
    }
    let points = region.required_points;
    let fold = |pick: fn(&(f64, f64)) -> f64, min: bool| {
        points.iter().map(pick).fold(
            if min {
                f64::INFINITY
            } else {
                f64::NEG_INFINITY
            },
            if min { f64::min } else { f64::max },
        )
    };
    let (west, east) = (fold(|p| p.0, true), fold(|p| p.0, false));
    let (south, north) = (fold(|p| p.1, true), fold(|p| p.1, false));
    let (dx, dy) = ((east - west) / 8.0 + 0.5, (north - south) / 8.0 + 0.5);
    Bounds {
        west: (west - dx).max(outer.west),
        south: (south - dy).max(outer.south),
        east: (east + dx).min(outer.east),
        north: (north + dy).min(outer.north),
    }
}

pub fn run(args: &[String]) -> Result<(), String> {
    let [table_path, list_path] = args else {
        return Err(
            "usage: terrain-sources <terrain_sources_data.rs> <terrain-sources.txt>".into(),
        );
    };
    let transport = HttpTransport::new();
    let info = transport
        .probe(COVERAGE_INDEX_URL)
        .map_err(|e| format!("{COVERAGE_INDEX_URL}: {e}"))?;
    let length = info.length.ok_or("the server gave no length")?;
    if length > 64 << 20 {
        return Err(format!("the coverage index is {length} bytes"));
    }
    let date = info
        .last_modified
        .as_deref()
        .and_then(iso_date)
        .ok_or("the server gave no date")?;
    let (_, file) = transport
        .fetch_range(COVERAGE_INDEX_URL, 0, length, None)
        .map_err(|e| e.to_string())?;
    let slice = |offset: u64, length: u64| {
        let start = usize::try_from(offset).map_err(std::io::Error::other)?;
        let end = start + usize::try_from(length).map_err(std::io::Error::other)?;
        file.get(start..end)
            .ok_or_else(|| std::io::Error::other("range outside the coverage index"))
    };
    let mut index = Index::open(|offset, length| slice(offset, length).map(<[u8]>::to_vec))
        .map_err(|e| e.to_string())?;

    // Tiles share their contents widely: each distinct one is read once.
    let mut contents: HashMap<(u64, u32), Vec<Key>> = HashMap::new();
    let mut groups: BTreeMap<Key, Group> = BTreeMap::new();
    let mut by_region: BTreeMap<&str, BTreeSet<Key>> = BTreeMap::new();
    for region in &regions::ALL {
        let (x0, x1, y0, y1) = tile_range(TERRAIN_MAX_ZOOM, &terrain_box(region));
        let mut locations = BTreeSet::new();
        for x in x0..=x1 {
            for y in y0..=y1 {
                if let Some(location) = index
                    .locate(TERRAIN_MAX_ZOOM, x, y)
                    .map_err(|e| e.to_string())?
                {
                    locations.insert(location);
                }
            }
        }
        let keys = by_region.entry(region.id).or_default();
        for location in locations {
            if let Entry::Vacant(entry) = contents.entry(location) {
                let bytes = slice(location.0, u64::from(location.1)).map_err(|e| e.to_string())?;
                let tile: BTreeMap<String, serde_json::Value> =
                    serde_json::from_slice(bytes).map_err(|e| format!("coverage tile: {e}"))?;
                let mut tile_keys = Vec::new();
                for (source, details) in tile {
                    let text = |name: &str| {
                        details[name]
                            .as_str()
                            .map(|s| s.trim().to_owned())
                            .filter(|s| !s.is_empty())
                            .ok_or_else(|| format!("source {source} has no {name}"))
                    };
                    let key = (text("producer_short")?, text("producer")?, text("license")?);
                    let resolution = details["resolution"]
                        .as_f64()
                        .ok_or_else(|| format!("source {source} has no resolution"))?;
                    let group = groups.entry(key.clone()).or_insert(Group {
                        website: text("website")?,
                        resolution,
                    });
                    if resolution < group.resolution {
                        group.resolution = resolution;
                        group.website = text("website")?;
                    }
                    tile_keys.push(key);
                }
                entry.insert(tile_keys);
            }
            keys.extend(contents[&location].iter().cloned());
        }
        if keys.is_empty() {
            return Err(format!("no terrain sources cover {}", region.id));
        }
    }

    let position: HashMap<&Key, usize> = groups.keys().enumerate().map(|(i, k)| (k, i)).collect();
    // Japan's licence is its terms of use and, after a full-width slash, the
    // approval under the Survey Act, which must be shown with the map.
    let notice = |license: &str| {
        license
            .split('／')
            .map(str::trim)
            .find(|part| part.contains("承認"))
            .unwrap_or("")
            .to_owned()
    };
    let mut table = format!(
        "// Generated by `offline-maps-dev terrain-sources` from Mapterhorn's coverage index\n\
         // ({COVERAGE_INDEX_URL}) of {date}; do not edit.\n\
         use super::TerrainSource;\n\n\
         /// The date of the coverage index the table was made from.\n\
         pub const COVERAGE_DATE: &str = {date:?};\n\n\
         pub static SOURCES: [TerrainSource; {}] = [\n",
        groups.len()
    );
    let mut list = format!(
        "Mapterhorn's elevation sources in the regions Offline Maps offers, from its coverage index of {date}. \
         Each line gives the producer, the licence and the source's page.\n"
    );
    for ((short, producer, license), group) in &groups {
        let _ = write!(
            table,
            "    TerrainSource {{\n        short: {short:?},\n        producer: {producer:?},\n        \
             license: {license:?},\n        website: {:?},\n        notice: {:?},\n    }},\n",
            group.website,
            notice(license)
        );
        let _ = writeln!(
            list,
            "\n{short}: {}. {license}. {}",
            producer.trim_end_matches('.'),
            group.website
        );
    }
    let _ = write!(
        table,
        "];\n\n/// Each region's sources as indexes into `SOURCES`, finest resolution first; by region ID.\n\
         pub static REGIONS: [(&str, &[u16]); {}] = [\n",
        by_region.len()
    );
    for (id, keys) in &by_region {
        let mut keys: Vec<&Key> = keys.iter().collect();
        keys.sort_by(|a, b| {
            groups[*a]
                .resolution
                .total_cmp(&groups[*b].resolution)
                .then_with(|| a.cmp(b))
        });
        let indexes: Vec<String> = keys.iter().map(|k| position[*k].to_string()).collect();
        let _ = writeln!(table, "    ({id:?}, &[{}]),", indexes.join(", "));
    }
    table.push_str("];\n");
    std::fs::write(table_path, table).map_err(|e| format!("{table_path}: {e}"))?;
    std::fs::write(list_path, list).map_err(|e| format!("{list_path}: {e}"))?;

    let most = by_region
        .iter()
        .max_by_key(|(_, keys)| keys.len())
        .map(|(id, keys)| format!("{id} has the most, {}", keys.len()))
        .unwrap_or_default();
    println!(
        "coverage index of {date}: {} sources for {} regions; {most}",
        groups.len(),
        by_region.len()
    );
    Ok(())
}
