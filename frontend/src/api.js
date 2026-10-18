// Thin wrapper around the status-service REST API. All paths are relative (/api/...) so the
// same build works behind nginx (Docker) and the Vite dev proxy.

async function getJson(path) {
  const res = await fetch(path);
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`${path} returned HTTP ${res.status}`);
  return res.json();
}

export const api = {
  devices: (search, page, size) =>
    getJson(`/api/devices?search=${encodeURIComponent(search)}&page=${page}&size=${size}`),
  device: (deviceId) => getJson(`/api/devices/${encodeURIComponent(deviceId)}`),
  history: (deviceId, limit = 25) =>
    getJson(`/api/devices/${encodeURIComponent(deviceId)}/history?limit=${limit}`),
  stats: () => getJson('/api/stats'),
  health: () => getJson('/api/health'),
  rejected: (limit = 10) => getJson(`/api/rejected-events?limit=${limit}`),
};
