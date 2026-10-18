# Manual demo guide

Start from a clean stack so the numbers match:

```bash
docker compose down -v
docker compose up -d --build
docker compose ps            # wait until status-service and ingest-api are "running"
curl http://localhost:8080/api/health
```

Open the dashboard at http://localhost:3000 and Kafka UI at http://localhost:8090.

## 1. Normal flow: 100 devices × 50 = 5,000 events

```bash
docker compose run --rm simulator seed --verify
```

Expect `PASS: all 100 devices report their newest reading as latest status` and stats
`historyEvents=5000, devices=100`. Dashboard: 100 devices across 5 pages. Kafka UI: the
`device.status` topic has 6 partitions with roughly 5,000 messages spread over them, and the
`device-status-service` consumer group has lag 0.

Check directly in PostgreSQL:

```bash
docker compose exec postgres psql -U devicestatus -c "SELECT count(*) FROM device_event_history;"
docker compose exec postgres psql -U devicestatus -c "SELECT count(*) FROM device_latest_status;"
```

## 2. Duplicate · 3. Out of order · 4. Invalid

```bash
docker compose run --rm simulator scenario
```

The script creates a fresh `demo-…` device and prints `PASS` lines for:

- **Duplicate:** the same event sent 3 more times → history count unchanged, latest unchanged.
- **Out of order:** a newer reading followed by an older one → latest is still the newer one; the
  older one appears in history only.
- **Invalid:** impossible latitude, missing deviceId, battery 150, bad timestamp → 4 rows under
  "Recently rejected events" on the dashboard; health still `UP`, consumer `RUNNING`.

Run them one at a time with `--name duplicate`, `--name out-of-order` or `--name invalid`.

Manual version with curl (PowerShell users: use `curl.exe`):

```bash
curl -X POST localhost:8081/api/events -H "Content-Type: application/json" \
  -d '{"eventId":"m-2","deviceId":"manual-1","capturedAt":"2026-10-16T12:05:00Z","latitude":34.2,"longitude":-118.5,"batteryPercent":80,"status":"ACTIVE"}'
curl -X POST localhost:8081/api/events -H "Content-Type: application/json" \
  -d '{"eventId":"m-1","deviceId":"manual-1","capturedAt":"2026-10-16T12:00:00Z","latitude":34.1,"longitude":-118.5,"batteryPercent":81,"status":"ACTIVE"}'
curl localhost:8080/api/devices/manual-1          # eventId is still m-2
curl localhost:8080/api/devices/manual-1/history  # both events, m-2 first
```

## 5. Restart

```bash
docker compose restart status-service
curl http://localhost:8080/api/stats      # same historyEvents / devices as before the restart
```

Also try: stop the consumer, send events, start it again - they are processed from Kafka on startup.

```bash
docker compose stop status-service
docker compose run --rm simulator seed --devices 10 --messages 5
docker compose start status-service      # the 50 queued events get processed
```

## 6. UI

In the dashboard, type `device-042` in the search box and press Enter (or click a row). The detail
panel shows the latest latitude/longitude, battery, status and the 25 most recent history events,
newest first, with the Kafka partition/offset each came from. The current latest event is highlighted.

## Live tracking

```bash
docker compose run --rm simulator live --devices 5 --interval 10
```

Five devices report every 10 seconds; the dashboard refreshes every 3 seconds and the coordinates move.

## Logs

```bash
docker compose logs -f status-service     # LATEST_UPDATED / STORED_OLDER_EVENT / DUPLICATE_IGNORED / REJECTED lines
```
