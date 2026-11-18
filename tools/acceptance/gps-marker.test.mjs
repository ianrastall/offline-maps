// Exercise the page's actual message handlers at the MapLibre boundary.
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import test from 'node:test';

test('GPS creates a positioned marker, follows fresh fixes and keeps stale fixes distinct', () => {
  const source = readFileSync(new URL('../../web/map.js', import.meta.url), 'utf8');
  const markers = [], moves = [];
  class Marker {
    constructor({ element }) { this.element = element; markers.push(this); }
    setLngLat(coordinates) { this.coordinates = coordinates; return this; }
    addTo() { assert.ok(this.coordinates, 'MapLibre needs coordinates before addTo'); return this; }
    getElement() { return this.element; }
    remove() { this.removed = true; }
  }
  const environment = {
    init() {},
    map: { getZoom: () => 6 }, positionMarker: null,
    maplibregl: { Marker }, move: options => moves.push(options),
    document: { createElement: () => ({
      classes: {}, attributes: {}, style: { setProperty() {} },
      classList: { toggle(name, value) { this[name] = value; } },
      setAttribute(name, value) { this.attributes[name] = value; },
    }) },
  };
  const start = source.indexOf('function prepareSnapshot(');
  const end = source.indexOf("\nhost?.addEventListener", start);
  assert.ok(start >= 0 && end > start);
  const handlers = vm.runInNewContext(source.slice(start, end) + '\nhandlers', environment);
  const fix = { latitude: 41.8819, longitude: -87.6278, accuracy: 5, follow: true };
  handlers.position(fix);
  assert.equal(markers.length, 1);
  assert.equal(moves.length, 1);
  assert.equal(moves[0].zoom, 14);
  assert.equal(markers[0].coordinates[0], fix.longitude);
  handlers.position({ ...fix, longitude: -87.62, follow: false });
  assert.equal(markers.length, 1, 'reuse the marker as fixes arrive');
  assert.equal(moves.length, 1, 'panning suspends following');
  handlers.position({ ...fix, stale: true, bearing: 90 });
  assert.equal(moves.length, 1, 'a stale fix cannot move the map');
  assert.equal(markers[0].element.classList.stale, true);
  assert.match(markers[0].element.attributes['aria-label'], /no current fix/);
  handlers.position({});
  assert.equal(markers[0].removed, true, 'GPS off removes the marker');
});
