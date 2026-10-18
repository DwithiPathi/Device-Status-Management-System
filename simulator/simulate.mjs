#!/usr/bin/env node
// Device simulator: pretends to be N GPS key fobs / watches sending location readings to the
// Ingest API (HTTP). No dependencies: needs Node 18+ (built-in fetch).
//
//   node simulate.mjs seed      100 devices x 50 readings = 5,000 events as fast as allowed
//   node simulate.mjs live      5 devices, one reading every 10 s each, until Ctrl+C
//   node simulate.mjs scenario  step-by-step demo of duplicate / out-of-order / invalid events
//
// Run `node simulate.mjs --help` for every option.

import { parseArgs } from 'node:util';

const HELP = `
Usage: node simulate.mjs <seed|live|scenario> [options]

Common options
  --ingest-url <url>       Ingest API base URL        (default $INGEST_URL or http://localhost:8081)
  --status-url <url>       Status service base URL    (default $STATUS_URL or http://localhost:8080)
  --devices <n>            Number of devices          (seed: 100, live: 5)
  --prefix <text>          Device ID prefix           (default "device")

seed
  --messages <n>           Readings per device        (default 50)
  --rate <n>               Max events per second, 0 = unlimited (default 1000)
  --batch-size <n>         Events per HTTP request    (default 100)
  --duplicates <ratio>     Extra re-sends of already-sent events, e.g. 0.05 = 5% (default 0)
  --out-of-order <ratio>   Fraction of events delayed to the end of the stream (default 0)
  --invalid <n>            Number of invalid events to mix in (default 0)
  --verify                 Afterwards, check every device's latest status via the API

live
  --interval <seconds>     Seconds between readings per device (default 10)
  --duration <seconds>     Stop after this long, 0 = run until Ctrl+C (default 0)

scenario
  --name <all|duplicate|out-of-order|invalid>   (default all)
`;

const { values: opts, positionals } = parseArgs({
  allowPositionals: true,
  options: {
    help: { type: 'boolean', short: 'h', default: false },
    'ingest-url': { type: 'string', default: process.env.INGEST_URL ?? 'http://localhost:8081' },
    'status-url': { type: 'string', default: process.env.STATUS_URL ?? 'http://localhost:8080' },
    devices: { type: 'string' },
    prefix: { type: 'string', default: 'device' },
    messages: { type: 'string', default: '50' },
    rate: { type: 'string', default: '1000' },
    'batch-size': { type: 'string', default: '100' },
    duplicates: { type: 'string', default: '0' },
    'out-of-order': { type: 'string', default: '0' },
    invalid: { type: 'string', default: '0' },
    verify: { type: 'boolean', default: false },
    interval: { type: 'string', default: '10' },
    duration: { type: 'string', default: '0' },
    name: { type: 'string', default: 'all' },
  },
});

const mode = positionals[0];
if (opts.help || !['seed', 'live', 'scenario'].includes(mode)) {
  console.log(HELP);
  process.exit(opts.help ? 0 : 1);
}

const INGEST = opts['ingest-url'].replace(/\/$/, '');
const STATUS = opts['status-url'].replace(/\/$/, '');
const num = (name, fallback) => {
  const raw = opts[name] ?? fallback;
  const n = Number(raw);
  if (!Number.isFinite(n) || n < 0) throw new Error(`--${name} must be a non-negative number, got "${raw}"`);
  return n;
};

// ---------------------------------------------------------------------------------------------
// Device model
// ---------------------------------------------------------------------------------------------

// Small deterministic PRNG so device-007 always starts in the same neighbourhood.
function mulberry32(seed) {
  return () => {
    seed |= 0;
    seed = (seed + 0x6d2b79f5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const pad = (n, width) => String(n).padStart(width, '0');
const round = (x, digits = 6) => Number(x.toFixed(digits));
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const runId = Date.now().toString(36); // keeps event IDs unique across simulator runs

class Device {
  constructor(index, prefix) {
    this.id = `${prefix}-${pad(index, 3)}`;
    this.random = mulberry32(index * 7919);
    // Start somewhere in greater Los Angeles.
    this.lat = 34.05 + (this.random() - 0.5) * 0.6;
    this.lon = -118.25 + (this.random() - 0.5) * 0.8;
    this.heading = this.random() * 2 * Math.PI;
    this.battery = 60 + Math.floor(this.random() * 41);
    this.seq = 0;
  }

  /** Next plausible reading: walk/drive a little, drain the battery a little. */
  next(capturedAtMs) {
    this.seq += 1;
    this.heading += (this.random() - 0.5) * 0.8;
    const step = 0.0002 + this.random() * 0.0015; // ~20 m .. ~190 m
    this.lat = Math.max(-89.9, Math.min(89.9, this.lat + Math.cos(this.heading) * step));
    this.lon = Math.max(-179.9, Math.min(179.9, this.lon + Math.sin(this.heading) * step));
    if (this.random() < 0.3) this.battery = Math.max(1, this.battery - 1);
    const moving = step > 0.0005;
    return {
      eventId: `evt-${runId}-${this.id}-${pad(this.seq, 4)}`,
      deviceId: this.id,
      capturedAt: new Date(capturedAtMs).toISOString(),
      latitude: round(this.lat),
      longitude: round(this.lon),
      batteryPercent: this.battery,
      status: this.battery < 15 ? 'LOW_BATTERY' : moving ? 'ACTIVE' : 'IDLE',
    };
  }
}

function invalidEvents(count, prefix) {
  const now = new Date().toISOString();
  const base = (i) => ({
    eventId: `bad-${runId}-${i}`,
    deviceId: `${prefix}-001`,
    capturedAt: now,
    latitude: 34.05,
    longitude: -118.25,
    batteryPercent: 50,
    status: 'ACTIVE',
  });
  const breakers = [
    (e) => ({ ...e, latitude: 999 }),                       // impossible latitude
    (e) => { const { deviceId, ...rest } = e; return rest; }, // missing device ID
    (e) => ({ ...e, batteryPercent: 150 }),                  // impossible battery
    (e) => ({ ...e, capturedAt: 'not-a-timestamp' }),        // malformed time
    (e) => ({ ...e, longitude: -500 }),                      // impossible longitude
    (e) => ({ ...e, status: 'TELEPORTING' }),                // unknown status
  ];
  return Array.from({ length: count }, (_, i) => breakers[i % breakers.length](base(i)));
}

// ---------------------------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------------------------

async function post(path, body) {
  const res = await fetch(`${INGEST}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`POST ${path} -> HTTP ${res.status}: ${await res.text()}`);
  return res.json();
}

async function getJson(path) {
  const res = await fetch(`${STATUS}${path}`);
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`GET ${path} -> HTTP ${res.status}`);
  return res.json();
}

async function waitFor(description, check, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      if (await check()) return true;
    } catch { /* service may still be catching up */ }
    await sleep(500);
  }
  console.log(`  ! timed out waiting for: ${description}`);
  return false;
}

// ---------------------------------------------------------------------------------------------
// seed: bulk load (default 100 x 50 = 5,000 events)
// ---------------------------------------------------------------------------------------------

async function seed() {
  const deviceCount = num('devices', 100);
  const perDevice = num('messages');
  const rate = num('rate');
  const batchSize = Math.max(1, num('batch-size'));
  const dupRatio = num('duplicates');
  const lateRatio = num('out-of-order');
  const invalidCount = num('invalid');

  const devices = Array.from({ length: deviceCount }, (_, i) => new Device(i + 1, opts.prefix));
  const stepMs = 30_000; // one reading every 30 s of simulated time
  const startMs = Date.now() - perDevice * stepMs;

  // Readings in capture-time order, devices interleaved, like real traffic.
  const unique = [];
  for (let s = 1; s <= perDevice; s++) {
    for (const d of devices) unique.push(d.next(startMs + s * stepMs));
  }
  const expectedLatest = new Map(devices.map((d) => [d.id, `evt-${runId}-${d.id}-${pad(perDevice, 4)}`]));

  // Out-of-order: move some readings to the end so they arrive after newer ones.
  const random = mulberry32(12345);
  const inOrder = [];
  const late = [];
  for (const e of unique) (random() < lateRatio ? late : inOrder).push(e);
  let stream = [...inOrder, ...late];

  // Duplicates: re-send already-sent readings (same eventId) at a later position.
  const dupCount = Math.round(unique.length * dupRatio);
  for (let i = 0; i < dupCount; i++) {
    const pos = Math.floor(random() * stream.length);
    const original = stream[Math.floor(random() * (pos + 1))];
    stream.splice(pos + 1, 0, original);
  }

  for (const bad of invalidEvents(invalidCount, opts.prefix)) {
    stream.splice(Math.floor(random() * stream.length), 0, bad);
  }

  console.log(`Seeding ${unique.length} unique readings (${deviceCount} devices x ${perDevice})`
    + ` + ${dupCount} duplicates + ${invalidCount} invalid, ${late.length} sent late -> ${stream.length} messages`);
  console.log(`Target: ${INGEST}/api/events/batch  batch=${batchSize}  rate=${rate || 'unlimited'}/s  run=${runId}`);

  const started = Date.now();
  let sent = 0;
  for (let i = 0; i < stream.length; i += batchSize) {
    const batch = stream.slice(i, i + batchSize);
    await post('/api/events/batch', batch);
    sent += batch.length;
    if (rate > 0) {
      const dueAt = started + (sent / rate) * 1000;
      if (dueAt > Date.now()) await sleep(dueAt - Date.now());
    }
    if (sent % 1000 < batchSize || sent === stream.length) {
      process.stdout.write(`  sent ${sent}/${stream.length}\r`);
    }
  }
  const seconds = (Date.now() - started) / 1000;
  console.log(`\nDone: ${sent} messages in ${seconds.toFixed(1)} s (${Math.round(sent / seconds)} msg/s)`);

  if (opts.verify) await verify(expectedLatest);
}

async function verify(expectedLatest) {
  console.log(`\nVerifying latest status of ${expectedLatest.size} devices via ${STATUS} ...`);
  const pending = new Map(expectedLatest);
  await waitFor('all devices to show their newest reading', async () => {
    const ids = [...pending.keys()];
    // Check in small parallel chunks so we do not hammer the API.
    for (let i = 0; i < ids.length; i += 20) {
      await Promise.all(ids.slice(i, i + 20).map(async (id) => {
        const status = await getJson(`/api/devices/${encodeURIComponent(id)}`);
        if (status?.eventId === pending.get(id)) pending.delete(id);
      }));
    }
    return pending.size === 0;
  }, 120000);

  const stats = await getJson('/api/stats');
  console.log('Stats:', JSON.stringify(stats));
  if (pending.size === 0) {
    console.log(`PASS: all ${expectedLatest.size} devices report their newest reading as latest status.`);
  } else {
    console.log(`FAIL: ${pending.size} devices do not show the expected latest reading, e.g. ${[...pending.keys()].slice(0, 5)}`);
    process.exitCode = 1;
  }
}

// ---------------------------------------------------------------------------------------------
// live: a handful of devices reporting every N seconds (good for watching the dashboard)
// ---------------------------------------------------------------------------------------------

async function live() {
  const deviceCount = num('devices', 5);
  const intervalMs = num('interval') * 1000;
  const durationMs = num('duration') * 1000;
  const devices = Array.from({ length: deviceCount }, (_, i) => new Device(i + 1, opts.prefix));
  const stopAt = durationMs > 0 ? Date.now() + durationMs : Infinity;

  console.log(`Live mode: ${deviceCount} devices, one reading each every ${intervalMs / 1000}s. Ctrl+C to stop.`);
  let stopping = false;
  process.on('SIGINT', () => { stopping = true; console.log('\nStopping...'); });

  while (!stopping && Date.now() < stopAt) {
    const tickStart = Date.now();
    await Promise.all(devices.map(async (d) => {
      const event = d.next(Date.now());
      try {
        await post('/api/events', event);
        console.log(`${event.capturedAt}  ${event.deviceId}  lat=${event.latitude}  lon=${event.longitude}`
          + `  battery=${event.batteryPercent}%  ${event.status}`);
      } catch (err) {
        console.error(`${event.deviceId}: send failed (${err.message}); the device will retry next tick`);
      }
    }));
    const wait = intervalMs - (Date.now() - tickStart);
    // Sleep in small slices so Ctrl+C is responsive.
    for (let waited = 0; waited < wait && !stopping; waited += 250) await sleep(Math.min(250, wait - waited));
  }
}

// ---------------------------------------------------------------------------------------------
// scenario: the demo checks from the assignment, one at a time, with the result printed
// ---------------------------------------------------------------------------------------------

async function scenario() {
  const name = opts.name;
  const deviceId = `demo-${runId}`;
  const now = Date.now();
  const reading = (suffix, offsetSec, lat) => ({
    eventId: `${deviceId}-${suffix}`,
    deviceId,
    capturedAt: new Date(now + offsetSec * 1000).toISOString(),
    latitude: lat,
    longitude: -118.25,
    batteryPercent: 77,
    status: 'ACTIVE',
  });
  const history = async () => (await getJson(`/api/devices/${deviceId}/history?limit=50`))?.totalEvents ?? 0;
  const latest = async () => (await getJson(`/api/devices/${deviceId}`))?.eventId;

  const first = reading('t0', -120, 34.0);
  console.log(`Demo device: ${deviceId}`);
  await post('/api/events', first);
  await waitFor('first reading to be stored', async () => (await latest()) === first.eventId);

  if (name === 'all' || name === 'duplicate') {
    console.log('\n[Duplicate] Re-sending the exact same event 3 times');
    const before = await history();
    for (let i = 0; i < 3; i++) await post('/api/events', first);
    await sleep(2000);
    const after = await history();
    console.log(`  history count before=${before} after=${after}, latest=${await latest()}`);
    console.log(after === before ? '  PASS: duplicates ignored' : '  FAIL: history changed');
  }

  if (name === 'all' || name === 'out-of-order') {
    console.log('\n[Out of order] Sending a NEWER reading, then an OLDER one');
    const newer = reading('t+60', 60, 34.2);
    const older = reading('t-60', -60, 34.1);
    await post('/api/events', newer);
    await post('/api/events', older);
    await waitFor('both readings in history', async () => (await history()) >= 3);
    const current = await latest();
    console.log(`  latest=${current} (expected ${newer.eventId})`);
    console.log(current === newer.eventId ? '  PASS: late reading did not overwrite newer status' : '  FAIL');
  }

  if (name === 'all' || name === 'invalid') {
    console.log('\n[Invalid] Sending impossible latitude, missing deviceId, battery 150, bad timestamp');
    const bad = invalidEvents(4, 'demo').map((e) => (e.deviceId ? { ...e, deviceId } : e));
    const before = (await getJson('/api/stats')).rejectedEvents;
    for (const e of bad) await post('/api/events', e);
    await waitFor('rejections to be recorded', async () => (await getJson('/api/stats')).rejectedEvents >= before + 4);
    const rejected = await getJson('/api/rejected-events?limit=4');
    for (const r of rejected) console.log(`  rejected: ${r.reason}`);
    const health = await getJson('/api/health');
    console.log(`  service health after bad input: ${health.status}, consumer ${health.kafkaConsumer}`);
  }

  console.log(`\nOpen the dashboard and search for "${deviceId}" to see it.`);
}

const modes = { seed, live, scenario };
modes[mode]().catch((err) => {
  console.error(`\nError: ${err.message}`);
  if (err.cause) console.error(`Cause: ${err.cause.message ?? err.cause}`);
  process.exit(1);
});
