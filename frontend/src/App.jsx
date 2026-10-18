import { useEffect, useState } from 'react';
import { api } from './api.js';
import DeviceTable from './components/DeviceTable.jsx';
import DeviceDetail from './components/DeviceDetail.jsx';
import RejectedEvents from './components/RejectedEvents.jsx';
import StatsBar from './components/StatsBar.jsx';

const PAGE_SIZE = 20;
const REFRESH_MS = 3000;

export default function App() {
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [devices, setDevices] = useState(null);
  const [stats, setStats] = useState(null);
  const [health, setHealth] = useState(null);
  const [rejected, setRejected] = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [refreshTick, setRefreshTick] = useState(0);
  const [lastRefresh, setLastRefresh] = useState(null);
  const [error, setError] = useState(null);

  // Tick every few seconds while auto-refresh is on; every view re-fetches on a new tick.
  useEffect(() => {
    if (!autoRefresh) return undefined;
    const id = setInterval(() => setRefreshTick((t) => t + 1), REFRESH_MS);
    return () => clearInterval(id);
  }, [autoRefresh]);

  useEffect(() => {
    let cancelled = false;
    Promise.all([api.devices(search.trim(), page, PAGE_SIZE), api.stats(), api.health(), api.rejected(10)])
      .then(([d, s, h, r]) => {
        if (cancelled) return;
        setDevices(d);
        setStats(s);
        setHealth(h);
        setRejected(r ?? []);
        setError(null);
        setLastRefresh(new Date());
      })
      .catch((e) => {
        if (cancelled) return;
        setError(`Cannot reach the status service (${e.message}).`);
        setHealth(null);
      });
    return () => { cancelled = true; };
  }, [search, page, refreshTick]);

  const onSearch = (value) => {
    setSearch(value);
    setPage(0);
  };

  const onSubmitSearch = (e) => {
    e.preventDefault();
    // Enter opens the detail view: the single match if the filter narrowed it down to one device,
    // otherwise the typed ID itself (the detail view says so if that device is unknown).
    const typed = search.trim();
    if (!typed) return;
    const exact = devices?.items.find((d) => d.deviceId.toLowerCase() === typed.toLowerCase());
    const only = devices?.items.length === 1 ? devices.items[0] : null;
    setSelectedId(exact?.deviceId ?? only?.deviceId ?? typed);
  };

  return (
    <div className="app">
      <header>
        <div>
          <h1>Device Status Dashboard</h1>
          <p className="muted">Latest known location of every tracker, from the central status service.</p>
        </div>
        <div className="header-right">
          <HealthPill health={health} />
          <label className="toggle">
            <input type="checkbox" checked={autoRefresh} onChange={(e) => setAutoRefresh(e.target.checked)} />
            Auto-refresh ({REFRESH_MS / 1000}s)
          </label>
          <button onClick={() => setRefreshTick((t) => t + 1)}>Refresh</button>
          {lastRefresh && <span className="muted small">Updated {lastRefresh.toLocaleTimeString()}</span>}
        </div>
      </header>

      {error && <div className="banner-error">{error}</div>}

      <StatsBar stats={stats} />

      <main className={selectedId ? 'with-detail' : ''}>
        <section className="panel">
          <form className="search" onSubmit={onSubmitSearch}>
            <input
              type="search"
              placeholder="Search by device ID, e.g. device-001"
              value={search}
              onChange={(e) => onSearch(e.target.value)}
              aria-label="Search by device ID"
            />
            <button type="submit">Open</button>
          </form>
          <DeviceTable page={devices} selectedId={selectedId} onSelect={setSelectedId} onPage={setPage} />
        </section>

        {selectedId && (
          <DeviceDetail deviceId={selectedId} refreshTick={refreshTick} onClose={() => setSelectedId(null)} />
        )}
      </main>

      <section className="panel">
        <h2>Recently rejected events</h2>
        <RejectedEvents events={rejected} />
      </section>
    </div>
  );
}

function HealthPill({ health }) {
  if (!health) return <span className="pill pill-down">Service unreachable</span>;
  const ok = health.status === 'UP' && health.kafkaConsumer === 'RUNNING';
  return (
    <span className={`pill ${ok ? 'pill-up' : 'pill-warn'}`}>
      DB {health.database} · Consumer {health.kafkaConsumer}
    </span>
  );
}
