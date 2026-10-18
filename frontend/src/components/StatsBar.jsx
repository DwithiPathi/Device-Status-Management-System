import { fmtNumber } from '../format.js';

const OUTCOME_LABELS = {
  LATEST_UPDATED: 'Latest updated',
  STORED_OLDER_EVENT: 'Late (history only)',
  DUPLICATE_IGNORED: 'Duplicates ignored',
  REJECTED: 'Rejected',
};

export default function StatsBar({ stats }) {
  const since = stats?.processedSinceStartup ?? {};
  return (
    <section className="stats">
      <div className="stat stat-primary">
        <span className="stat-value">{fmtNumber(stats?.devices)}</span>
        <span className="stat-label">Devices (latest-status rows)</span>
      </div>
      <div className="stat stat-primary">
        <span className="stat-value">{fmtNumber(stats?.historyEvents)}</span>
        <span className="stat-label">Unique events in history</span>
      </div>
      <div className="stat stat-primary">
        <span className="stat-value">{fmtNumber(stats?.rejectedEvents)}</span>
        <span className="stat-label">Rejected events</span>
      </div>
      <div className="stat stat-secondary">
        <span className="stat-label">Processed since service start</span>
        <ul>
          {Object.entries(OUTCOME_LABELS).map(([key, label]) => (
            <li key={key}>
              <span>{label}</span>
              <strong>{fmtNumber(since[key] ?? 0)}</strong>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}
