import { fmtTime } from '../format.js';

export default function RejectedEvents({ events }) {
  if (!events || events.length === 0) {
    return <p className="muted">No rejected events. Invalid messages (bad latitude, missing device ID, …) show up here.</p>;
  }
  return (
    <div className="table-wrap">
      <table className="compact">
        <thead>
          <tr>
            <th>Rejected at</th>
            <th>Device</th>
            <th>Reason</th>
            <th>Payload</th>
          </tr>
        </thead>
        <tbody>
          {events.map((r) => (
            <tr key={r.id}>
              <td>{fmtTime(r.rejectedAt)}</td>
              <td className="mono">{r.deviceId ?? <em className="muted">missing</em>}</td>
              <td>{r.reason}</td>
              <td className="mono small payload" title={r.payload}>{r.payload}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
