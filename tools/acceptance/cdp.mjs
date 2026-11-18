// Minimal Chrome DevTools Protocol client for the app's WebView2 (loopback only).
// node cdp.mjs info <out.json> | shot <out.png>
import { writeFileSync } from 'node:fs';
import { setTimeout as delay } from 'node:timers/promises';

const [, , cmd, out] = process.argv;
const endpoint = `http://127.0.0.1:${process.env.OFFLINE_MAPS_CDP_PORT || 9222}/json`;
let page;
for (let attempt = 0; attempt < 30 && !page; attempt++) {
  try {
    const targets = await (await fetch(endpoint, { signal: AbortSignal.timeout(1000) })).json();
    page = targets.find(t => t.type === 'page' && t.url === 'https://map.local/index.html');
  } catch {
    // The listener may be starting while the app opens its map store.
  }
  if (!page) await delay(250);
}
if (!page) throw Error('The local map page did not become available.');

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let id = 0;
const pending = new Map();
ws.onmessage = ev => {
  const m = JSON.parse(ev.data);
  if (m.id && pending.has(m.id)) {
    const finish = pending.get(m.id);
    pending.delete(m.id);
    finish(m);
  }
};
const send = (method, params = {}) => new Promise((resolve, reject) => {
  const request = ++id;
  const timer = setTimeout(() => reject(Error(`${method} timed out`)), 10_000);
  pending.set(request, message => {
    clearTimeout(timer);
    if (message.error) reject(Error(message.error.message));
    else resolve(message);
  });
  ws.send(JSON.stringify({ id: request, method, params }));
});

if (cmd === 'info') {
  const expr = `(() => {
    const res = performance.getEntriesByType('resource').map(e => ({ url: e.name, status: e.responseStatus, type: e.initiatorType }));
    const nav = performance.getEntriesByType('navigation').map(e => ({ url: e.name, status: e.responseStatus }));
    const remote = res.concat(nav).filter(e => !e.url.startsWith('https://map.local/') && !e.url.startsWith('blob:') && !e.url.startsWith('data:'));
    const kinds = {};
    for (const e of res) {
      const p = new URL(e.url).pathname.split('/')[1] || '';
      const k = p + ':' + e.status;
      kinds[k] = (kinds[k] || 0) + 1;
    }
    const canvas = document.querySelector('canvas.maplibregl-canvas');
    const attrib = document.querySelector('.maplibregl-ctrl-attrib-inner');
    const labels = [...document.querySelectorAll('.maplibregl-marker, .maplibregl-popup')].length;
    return { href: location.href, online: navigator.onLine, resources: res.length, byKind: kinds, remote,
      canvas: canvas ? [canvas.width, canvas.height] : null, viewport: [innerWidth, innerHeight],
      layout: ['html', 'body', '#map', 'canvas'].map(s => { const e = document.querySelector(s); const b = e?.getBoundingClientRect(); return { selector: s, width: b?.width, height: b?.height, cssHeight: e && getComputedStyle(e).height }; }),
      attribution: attrib ? attrib.innerText : null, markers: labels,
      focusedMap: canvas != null && document.hasFocus() && document.activeElement === canvas,
      mapFocusOutline: canvas?.matches(':focus-visible') ? getComputedStyle(canvas).outlineStyle : null };
  })()`;
  const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true });
  writeFileSync(out, JSON.stringify(r.result?.result?.value ?? r, null, 2));
} else if (cmd === 'shot') {
  const r = await send('Page.captureScreenshot', { format: 'png' });
  writeFileSync(out, Buffer.from(r.result.data, 'base64'));
}
ws.close();
