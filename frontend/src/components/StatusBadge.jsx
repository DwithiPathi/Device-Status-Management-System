export function StatusBadge({ status }) {
  return <span className={`badge badge-${(status ?? 'unknown').toLowerCase()}`}>{status ?? 'UNKNOWN'}</span>;
}

export function Battery({ percent }) {
  const level = percent < 15 ? 'low' : percent < 40 ? 'mid' : 'ok';
  return (
    <span className="battery" title={`${percent}%`}>
      <span className="battery-track">
        <span className={`battery-fill battery-${level}`} style={{ width: `${percent}%` }} />
      </span>
      <span className="battery-text">{percent}%</span>
    </span>
  );
}
