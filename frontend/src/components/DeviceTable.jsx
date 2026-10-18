import { fmtCoord, fmtTime, timeAgo } from '../format.js';
import { Battery, StatusBadge } from './StatusBadge.jsx';

export default function DeviceTable({ page, selectedId, onSelect, onPage }) {
  if (!page) return <p className="muted">Loading devices…</p>;
  if (page.items.length === 0) {
    return <p className="muted empty">No devices found. Start the simulator to send some readings.</p>;
  }

  const lastPage = Math.max(0, Math.ceil(page.total / page.size) - 1);
  return (
    <>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Device ID</th>
              <th className="num">Latitude</th>
              <th className="num">Longitude</th>
              <th>Status</th>
              <th>Battery</th>
              <th>Last captured</th>
            </tr>
          </thead>
          <tbody>
            {page.items.map((d) => (
              <tr
                key={d.deviceId}
                className={d.deviceId === selectedId ? 'selected' : ''}
                onClick={() => onSelect(d.deviceId)}
              >
                <td className="mono">{d.deviceId}</td>
                <td className="num mono">{fmtCoord(d.latitude)}</td>
                <td className="num mono">{fmtCoord(d.longitude)}</td>
                <td><StatusBadge status={d.status} /></td>
                <td><Battery percent={d.batteryPercent} /></td>
                <td>
                  {fmtTime(d.capturedAt)} <span className="muted small">({timeAgo(d.capturedAt)})</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="pager">
        <button onClick={() => onPage(page.page - 1)} disabled={page.page === 0}>← Prev</button>
        <span>
          Page {page.page + 1} of {lastPage + 1} · {page.total} devices
        </span>
        <button onClick={() => onPage(page.page + 1)} disabled={page.page >= lastPage}>Next →</button>
      </div>
    </>
  );
}
