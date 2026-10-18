# Device Status Management System

A small Life360 / Tile-style tracker backend. Simulated GPS key fobs send location readings; a
central service keeps the **latest known location of every device**, even when messages arrive
twice, late, out of order or broken.

```
 simulator (N fake fobs)
        │  HTTP POST /api/events[/batch]
        ▼
 ┌─────────────┐   key = deviceId   ┌──────────────────────────┐
 │ ingest-api  │ ─────────────────► │ Kafka topic device.status │  6 partitions
 │ Spring Boot │                    └────────────┬─────────────┘
 └─────────────┘                                 │ consumer group "device-status-service"
                                                 ▼
                                   ┌──────────────────────────┐      ┌────────────────────────┐
                                   │ status-service           │ ───► │ PostgreSQL             │
                                   │ validate → dedup →       │      │  device_event_history  │
                                   │ conditional upsert       │      │  device_latest_status  │
                                   │ REST API /api/devices    │      │  device_event_rejected │
                                   └────────────┬─────────────┘      └────────────────────────┘
                                                │ /api/...
                                                ▼
                                   React dashboard (http://localhost:3000)
```

| Folder | What it is |
|---|---|
| [`backend/ingest-api`](backend/ingest-api) | Spring Boot HTTP gateway. Devices POST JSON here; it publishes to Kafka keyed by `deviceId`. |
| [`backend/status-service`](backend/status-service) | **The central service.** Kafka consumer, validation, PostgreSQL storage, REST API. |
| [`frontend`](frontend) | React (Vite) dashboard: device table, search, detail view with history, rejected events. |
| [`simulator`](simulator) | Zero-dependency Node script that pretends to be the devices (seed / live / scenario modes). |
| [`compose.yaml`](compose.yaml) | Starts Kafka, Kafka UI, PostgreSQL, both services and the dashboard. |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | One-page design explanation, Kafka primer and known limitations. |
| [`docs/DEMO.md`](docs/DEMO.md) | Step-by-step manual demo of the six required tests. |

Stack: Java 21, Spring Boot 3.3, Spring Kafka, Apache Kafka 3.8 (KRaft), PostgreSQL 16, Flyway,
React 18 + Vite, Docker Compose.

## Quick start

Requirements: **Docker Desktop** (with Compose v2) running. Nothing else; Java and Node builds run inside Docker.
Run every command from the project folder (the one containing `compose.yaml`). On Windows, use PowerShell:

```powershell
cd $HOME\Projects\DeviceStatusManagementSystem
```

```bash
docker compose up -d --build
```

First build takes a few minutes (Maven and npm downloads). Then:

| URL | |
|---|---|
| http://localhost:3000 | Dashboard |
| http://localhost:8080/api/health | Status service health |
| http://localhost:8081/api/health | Ingest API health |
| http://localhost:8090 | Kafka UI (topics, partitions, messages, consumer lag) |

### Seed the 5,000 events (100 devices × 50 readings)

```bash
docker compose run --rm simulator seed --verify
```

`--verify` waits until the status service has processed everything and checks every device's
latest status is its newest reading. Expected result: 5,000 history rows, 100 latest-status rows.

Make it harder (duplicates, late messages and invalid data mixed in) - the latest status must still be correct:

```bash
docker compose run --rm simulator seed --duplicates 0.05 --out-of-order 0.1 --invalid 10 --verify
```

Other sizes: `--devices 1000 --messages 100 --rate 0` (rate 0 = as fast as possible).

### Live demo (5 devices, every 10 seconds)

```bash
docker compose run --rm simulator live --devices 5 --interval 10
```

Leave it running and watch the dashboard update (auto-refresh every 3 s). Ctrl+C to stop.

### Duplicate / out-of-order / invalid demo

```bash
docker compose run --rm simulator scenario
```

See [docs/DEMO.md](docs/DEMO.md) for the full manual demo script, including the restart test.

### Stop / reset

```bash
docker compose down        # stop, keep the PostgreSQL data
docker compose down -v     # stop and delete all data
```

## REST API (status-service, port 8080)

| Endpoint | Description |
|---|---|
| `GET /api/devices?search=&page=0&size=25` | Paginated latest status of every device, optional deviceId substring filter |
| `GET /api/devices/{deviceId}` | Latest status of one device (404 if unknown) |
| `GET /api/devices/{deviceId}/history?limit=20` | Recent unique events, newest first, with Kafka partition/offset |
| `GET /api/rejected-events?limit=20` | Recently rejected (invalid / malformed) messages and the reason |
| `GET /api/stats` | Row counts plus per-outcome counters since startup |
| `GET /api/health` | Database and Kafka consumer health |

Ingest API (port 8081): `POST /api/events` (one JSON event), `POST /api/events/batch` (JSON array,
max 1,000), `GET /api/health`. Both return `202 Accepted` once Kafka has acknowledged the events.

Event format:

```json
{
  "eventId": "event-0001",
  "deviceId": "device-001",
  "capturedAt": "2026-10-16T12:00:00Z",
  "latitude": 34.3917,
  "longitude": -118.5426,
  "batteryPercent": 85,
  "status": "ACTIVE"
}
```

`status` is one of `ACTIVE`, `IDLE`, `LOW_BATTERY`, `OFFLINE`. `capturedAt` is when the device took
the reading, not when the server received it.

## Automated tests

Requires JDK 21 and a running Docker (the Kafka/PostgreSQL tests start real containers with
Testcontainers and are skipped automatically if Docker is not running). Maven is downloaded by the wrapper.

```powershell
cd backend
.\mvnw.cmd test        # Windows PowerShell
./mvnw test             # macOS / Linux
```

| Test | Covers |
|---|---|
| `DeviceEventProcessorTest` | normal flow (10×20 events → 200 history rows, 10 latest rows), duplicate, out-of-order, equal-timestamp tie-break, invalid events, 8 threads applying 250 shuffled events concurrently |
| `KafkaPipelineTest` | full loop through real Kafka: duplicate + late + invalid message |
| `DeviceEventValidatorTest` | every validation rule |
| `IngestControllerTest` | ingest API accepts / rejects requests, returns 503 when Kafka is down |

## Running without Docker for the services (optional)

Start only the infrastructure, then run the apps from your IDE or terminal:

```bash
docker compose up -d kafka postgres kafka-ui
cd backend; .\mvnw.cmd -pl status-service spring-boot:run   # terminal 1 (PowerShell)
cd backend; .\mvnw.cmd -pl ingest-api spring-boot:run       # terminal 2
cd frontend && npm install && npm run dev                # terminal 3 -> http://localhost:5173
node simulator/simulate.mjs seed --verify                # terminal 4
```

The defaults in each `application.yml` point at `localhost:29092` (Kafka) and `localhost:5432` (PostgreSQL).

## Configuration

| Setting | Where | Default |
|---|---|---|
| Kafka topic / partitions | `app.kafka.topic`, `app.kafka.partitions` | `device.status`, 6 |
| Consumer threads | `app.kafka.consumer-concurrency` | 3 |
| Retry before dead-letter | `app.kafka.retry.*` | 5 attempts, 1 s → 10 s back-off |
| Dead-letter topic | `app.kafka.dlq-topic` | `device.status.dlq` |
| Max clock skew for `capturedAt` | `app.validation.max-future-skew` | 5m |
| DB / Kafka addresses | env `DB_URL`, `DB_USER`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS` | localhost |
| Simulator | `node simulator/simulate.mjs --help` | 100 devices × 50 |
