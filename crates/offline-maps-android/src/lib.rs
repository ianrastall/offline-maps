//! JNI bridge from the Android app to the Offline Maps core.
//!
//! The Kotlin side is `io.github.ianrastall.offlinemaps.core.NativeCore`. A core
//! is opened on a map folder and passed back as an opaque handle; every call
//! takes it. Failures and panics surface in Kotlin as `RuntimeException`s whose
//! message starts with "Rust error: " or "Rust panic: ".
//!
//! Status is returned as JSON in the shape the map page's `init` message uses
//! (the Windows shell builds the same from the protobuf `Status`), so the
//! Android map page and the native renderer read one description of what is
//! installed.
use std::fmt;

use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JByteArray, JClass, JObject, JString};
use jni::sys::{jdouble, jint, jlong};
use jni::{Env, EnvUnowned};
use offline_maps_ffi::messages as pb;
use offline_maps_ffi::service::Service;
use serde_json::{Value, json};

#[derive(Debug)]
struct BridgeError(String);

impl fmt::Display for BridgeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for BridgeError {}

impl From<jni::errors::Error> for BridgeError {
    fn from(e: jni::errors::Error) -> Self {
        Self(e.to_string())
    }
}

impl From<pb::Error> for BridgeError {
    fn from(e: pb::Error) -> Self {
        Self(e.message)
    }
}

type Result<T> = std::result::Result<T, BridgeError>;

/// Tile kinds, as numbered in `NativeCore`.
const TILE_MAP: jint = 0;
const TILE_OVERVIEW: jint = 1;
const TILE_TERRAIN: jint = 2;
const TILE_OVERLAY: jint = 3;

/// # Safety
/// `handle` must come from `open` and not yet be closed.
unsafe fn service<'a>(handle: jlong) -> Result<&'a Service> {
    unsafe { (handle as *const Service).as_ref() }
        .ok_or_else(|| BridgeError("The map core is not open.".into()))
}

fn text<'local>(env: &mut Env<'local>, value: &str) -> Result<JObject<'local>> {
    Ok(JObject::from(env.new_string(value)?))
}

fn bounds(b: &pb::Bounds) -> Value {
    json!({ "west": b.west, "south": b.south, "east": b.east, "north": b.north })
}

/// The page's view of what is installed: the `metadata`, `terrain` and
/// `overlays` members of its `init` message.
pub fn status_json(status: &pb::Status, overview: bool) -> Value {
    // With nothing installed, the overview of the 48 contiguous states.
    let fallback = pb::Bounds {
        west: -125.0,
        south: 24.0,
        east: -66.5,
        north: 49.5,
    };
    let installed = status.coverage.is_some();
    let coverage = status.coverage.as_ref().unwrap_or(&fallback);
    let regions: Vec<&str> = status
        .regions
        .iter()
        .filter(|r| r.map.is_some())
        .map(|r| r.id.as_str())
        .collect();
    let credits: Vec<Value> = status
        .regions
        .iter()
        .filter_map(|r| {
            let terrain = r.terrain.as_ref()?;
            let b = terrain.bounds.as_ref()?;
            let sources: Vec<Value> = terrain
                .sources
                .iter()
                .map(|s| {
                    json!({
                        "short": s.short_name,
                        "detail": format!("{} ({})", s.producer.trim_end_matches('.'), s.license),
                        "notice": s.notice,
                    })
                })
                .collect();
            Some(json!({ "bounds": [b.west, b.south, b.east, b.north], "sources": sources }))
        })
        .collect();
    json!({
        "places": status.places.iter().map(|p| json!({
            "id": p.id, "name": p.name, "note": p.note,
            "latitude": p.latitude, "longitude": p.longitude, "covered": p.covered,
        })).collect::<Vec<_>>(),
        "regions": status.regions.iter().filter(|r| r.map.is_some()).map(|r| json!({
            "id": r.id, "name": r.name, "date": r.map.as_ref().map(|m| &m.data_date),
            "extras": r.extras.is_some(), "terrain": r.terrain.is_some(), "overlays": r.overlays.is_some(),
            "extrasDate": r.extras.as_ref().map(|e| &e.data_date),
            "terrainDate": r.terrain.as_ref().map(|t| &t.data_date),
            "floodEffective": r.overlays.as_ref().map(|o| &o.flood_effective),
            "overlayBuilt": r.overlays.as_ref().map(|o| &o.built_at),
            "publicLands": r.overlays.as_ref().map(|o| &o.public_lands),
        })).collect::<Vec<_>>(),
        "placesProblem": status.places_problem,
        "searchReady": status.search_ready,
        "metadata": {
            "bounds": bounds(coverage),
            "minZoom": if installed { status.min_zoom } else { 0 },
            "maxZoom": if installed { status.max_zoom } else { 14 },
            "regions": regions,
            "overview": overview,
        },
        "terrain": status.terrain_coverage.as_ref().map(|t| json!({ "bounds": bounds(t), "credits": credits })),
        "overlays": status.overlay_coverage.as_ref().map(|o| json!({ "bounds": bounds(o) })),
        "problem": status.map_problem,
        "contentVersion": status.content_version,
    })
}

/// Opens the core on `store` (the map folder, created if missing). `overview`
/// is the built-in overview's file, or empty for none.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ianrastall_offlinemaps_core_NativeCore_open<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    store: JString<'local>,
    overview: JString<'local>,
) -> jlong {
    unowned
        .with_env(|env| -> Result<jlong> {
            let config = pb::Config {
                store_directory: store.try_to_string(env)?,
                overview_path: overview.try_to_string(env)?,
                prepared_extras_only: true,
                ..Default::default()
            };
            let service = Service::open(&config)?;
            Ok(Box::into_raw(Box::new(service)) as jlong)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// The installed maps as JSON (see `status_json`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ianrastall_offlinemaps_core_NativeCore_statusJson<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    overview: jni::sys::jboolean,
) -> JObject<'local> {
    unowned
        .with_env(|env| -> Result<JObject<'local>> {
            let service = unsafe { service(handle) }?;
            let value = status_json(&service.status(), overview);
            text(env, &value.to_string())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// A tile's bytes (decompressed vector tile, or WebP for terrain); empty where
/// there is none.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ianrastall_offlinemaps_core_NativeCore_tile<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    kind: jint,
    z: jint,
    x: jint,
    y: jint,
) -> JByteArray<'local> {
    unowned
        .with_env(|env| -> Result<JByteArray<'local>> {
            let service = unsafe { service(handle) }?;
            let (Ok(z), Ok(x), Ok(y)) = (u32::try_from(z), u32::try_from(x), u32::try_from(y))
            else {
                return Ok(env.byte_array_from_slice(&[])?);
            };
            let bytes = match kind {
                TILE_MAP => service.tile(z, x, y)?.unwrap_or_default(),
                TILE_OVERVIEW => {
                    service
                        .overview_tile(&pb::GetOverviewTile { z, x, y })?
                        .data
                }
                TILE_TERRAIN => service.terrain_tile(&pb::GetTerrainTile { z, x, y })?.data,
                TILE_OVERLAY => service.overlay_tile(&pb::GetOverlayTile { z, x, y })?.data,
                _ => return Err(BridgeError(format!("Unknown tile kind {kind}."))),
            };
            Ok(env.byte_array_from_slice(&bytes)?)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Fuel and charging stations and outdoor places as a GeoJSON FeatureCollection.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ianrastall_offlinemaps_core_NativeCore_stations<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> JObject<'local> {
    unowned
        .with_env(|env| -> Result<JObject<'local>> {
            let service = unsafe { service(handle) }?;
            let geojson = service.stations()?.geojson;
            text(env, &geojson)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// Releases the core. Must not be called while another call on it runs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ianrastall_offlinemaps_core_NativeCore_close<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    unowned
        .with_env(|_env| -> Result<()> {
            if handle != 0 {
                drop(unsafe { Box::from_raw(handle as *mut Service) });
            }
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ianrastall_offlinemaps_core_NativeCore_searchJson<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    query: JString<'local>,
    latitude: jdouble,
    longitude: jdouble,
) -> JObject<'local> {
    unowned
        .with_env(|env| -> Result<JObject<'local>> {
            let results = unsafe { service(handle) }?.search(&pb::Search {
                query: query.try_to_string(env)?,
                near: Some(pb::Position {
                    latitude,
                    longitude,
                }),
                limit: 30,
            })?;
            let items: Vec<_> = results
                .results
                .iter()
                .map(|r| {
                    json!({
                        "name": r.name, "category": r.category, "kind": r.kind, "near": r.near,
                        "latitude": r.latitude, "longitude": r.longitude,
                        "distanceKm": r.has_distance.then_some(r.distance_km), "detail": r.detail,
                    })
                })
                .collect();
            text(env, &json!(items).to_string())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ianrastall_offlinemaps_core_NativeCore_addPlace<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    name: JString<'local>,
    latitude: jdouble,
    longitude: jdouble,
) {
    unowned
        .with_env(|env| -> Result<()> {
            unsafe { service(handle) }?.add_place(&pb::AddPlace {
                name: name.try_to_string(env)?,
                note: String::new(),
                position: Some(pb::Position {
                    latitude,
                    longitude,
                }),
            })?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ianrastall_offlinemaps_core_NativeCore_removePlace<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jlong,
) {
    unowned
        .with_env(|_env| -> Result<()> {
            unsafe { service(handle) }?.remove_place(id as u64)?;
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ianrastall_offlinemaps_core_NativeCore_importTransfer<
    'local,
>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    staged: JString<'local>,
) -> JObject<'local> {
    unowned
        .with_env(|env| -> Result<JObject<'local>> {
            let path = staged.try_to_string(env)?;
            let id = unsafe { service(handle) }?.import_transfer(std::path::Path::new(&path))?;
            text(env, &id)
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ianrastall_offlinemaps_core_NativeCore_cancel<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) {
    unowned
        .with_env(|_env| -> Result<()> {
            unsafe { service(handle) }?.cancel();
            Ok(())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// The local catalog: opening the download list makes no network requests.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ianrastall_offlinemaps_core_NativeCore_catalogJson<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> JObject<'local> {
    unowned
        .with_env(|env| -> Result<JObject<'local>> {
            let catalog = unsafe { service(handle) }?.catalog();
            let regions: Vec<_> = catalog.regions.iter().map(|r| json!({
            "id": r.id, "name": r.name, "country": r.country, "continent": r.continent,
            "note": r.note, "mapBytes": r.package_bytes, "extrasBytes": r.prepared_extras_bytes,
            "terrainBytes": r.terrain_bytes, "overlayBytes": r.overlay_bytes,
        })).collect();
            text(env, &json!(regions).to_string())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ianrastall_offlinemaps_core_NativeCore_progressJson<
    'local,
>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> JObject<'local> {
    unowned
        .with_env(|env| -> Result<JObject<'local>> {
            let p = unsafe { service(handle) }?.progress();
            text(
                env,
                &json!({
                    "active": p.active, "phase": p.phase, "done": p.done, "total": p.total,
                    "cancelling": p.cancelling, "region": p.region_name, "target": p.target,
                })
                .to_string(),
            )
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

/// A single, resumable update. Run on a worker; progress and cancellation may
/// be called concurrently. The service activates only verified, prepared data.
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_ianrastall_offlinemaps_core_NativeCore_updateJson<'local>(
    mut unowned: EnvUnowned<'local>,
    _class: JClass<'local>,
    handle: jlong,
    region: JString<'local>,
    target: jint,
    check_only: jni::sys::jboolean,
) -> JObject<'local> {
    unowned
        .with_env(|env| -> Result<JObject<'local>> {
            let result = unsafe { service(handle) }?.update(&pb::Update {
                region_id: region.try_to_string(env)?,
                target,
                check_only: check_only != jni::sys::JNI_FALSE,
            })?;
            text(env, &json!({
            "upToDate": result.up_to_date, "available": result.available,
            "date": result.remote_date, "bytes": result.remote_bytes, "version": result.version,
        }).to_string())
        })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn status_without_maps_uses_the_contiguous_states() {
        let value = status_json(&pb::Status::default(), true);
        assert_eq!(value["metadata"]["bounds"]["west"], -125.0);
        assert_eq!(value["metadata"]["maxZoom"], 14);
        assert_eq!(value["metadata"]["overview"], true);
        assert!(value["terrain"].is_null());
    }

    #[test]
    fn status_lists_installed_regions_and_terrain_credits() {
        let b = pb::Bounds {
            west: -91.5,
            south: 36.9,
            east: -87.4,
            north: 42.5,
        };
        let status = pb::Status {
            coverage: Some(b),
            terrain_coverage: Some(b),
            min_zoom: 0,
            max_zoom: 14,
            regions: vec![pb::RegionStatus {
                id: "us-il".into(),
                map: Some(pb::MapInfo::default()),
                terrain: Some(pb::TerrainInfo {
                    bounds: Some(b),
                    sources: vec![pb::TerrainSource {
                        short_name: "USGS".into(),
                        producer: "U.S. Geological Survey.".into(),
                        license: "Public domain".into(),
                        ..Default::default()
                    }],
                    ..Default::default()
                }),
                ..Default::default()
            }],
            ..Default::default()
        };
        let value = status_json(&status, false);
        assert_eq!(value["metadata"]["regions"], json!(["us-il"]));
        assert_eq!(
            value["terrain"]["credits"][0]["sources"][0]["short"],
            "USGS"
        );
        assert_eq!(
            value["terrain"]["credits"][0]["sources"][0]["detail"],
            "U.S. Geological Survey (Public domain)"
        );
        assert!(value["overlays"].is_null());
    }
}
