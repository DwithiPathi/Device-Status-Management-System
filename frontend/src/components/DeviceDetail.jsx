import { useEffect, useState } from 'react';
import { api } from '../api.js';
import { fmtCoord, fmtTime, timeAgo } from '../format.js';
import { Battery, StatusBadge } from './StatusBadge.jsx';

export default function DeviceDetail({ deviceId, refreshTick, onClose }) {
  const [device, setDevice] = useState(undefined);
  const [history, setHistory] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([api.device(deviceId), api.history(deviceId, 25)])
      .then(([d, h]) => {
        if (cancelled) return;
        setDevice(d);
        setHistory(h);
        setError(null);
      })
      .catch((e) => !cancelled && setError(e.message));
    return () => { cancelled = true; };
  }, [deviceId, refreshTick]);

  return (
    <aside className="detail">
      <div className="detail-head">
        <h2 className="mono">{deviceId}</h2>
        <button className="link" onClick={onClose} aria-label="Close detail">✕</button>
      </div>

      {error && <p className="error">{error}</p>}
      {device === null && <p className="muted">No status stored for this device yet.</p>}

      {device && (
        <div className="latest">
          <div className="latest-coords">
            <div>
              <span className="label">Latitude</span>
              <span className="big mono">{fmtCoord(device.latitude)}</span>
            </div>
            <div>
              <span className="label">Longitude</span>
              <span className="big mono">{fmtCoord(device.longitude)}</span>
            </div>
          </div>
          <dl>
            <dt>Status</dt><dd><StatusBadge status={device.status} /></dd>
            <dt>Battery</dt><dd><Battery percent={device.batteryPercent} /></dd>
            <dt>Captured</dt><dd>{fmtTime(device.capturedAt)} <span className="muted small">({timeAgo(device.capturedAt)})</span></dd>
            <dt>Event ID</dt><dd className="mono small">{device.eventId}</dd>
            <dt>Row updated</dt><dd>{fmtTime(device.updatedAt)}</dd>
          </dl>
        </div>
      )}

      {history && (
        <>
          <h3>
            Recent history <span className="muted small">({history.events.length} of {history.totalEvents}, newest first)</span>
          </h3>
          <div className="table-wrap">
            <table className="compact">
              <thead>
                <tr>
                  <th>Captured</th>
                  <th className="num">Lat</th>
                  <th className="num">Lon</th>
                  <th>Batt.</th>
                  <th>Status</th>
                  <th title="Kafka partition @ offset">Kafka</th>
                </tr>
              </thead>
              <tbody>
                {history.events.map((e) => (
                  <tr key={e.eventId} className={device && e.eventId === device.eventId ? 'current' : ''}>
                    <td title={e.eventId}>{fmtTime(e.capturedAt)}</td>
                    <td className="num mono">{fmtCoord(e.latitude)}</td>
                    <td className="num mono">{fmtCoord(e.longitude)}</td>
                    <td className="num">{e.batteryPercent}%</td>
                    <td><StatusBadge status={e.status} /></td>
                    <td className="mono small">
                      {e.kafkaPartition == null ? '—' : `p${e.kafkaPartition}@${e.kafkaOffset}`}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </aside>
  );
}
