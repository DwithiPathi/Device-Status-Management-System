# Architecture and design choices

## Flow

1. **Device → ingest-api (HTTP).** Devices (here: the simulator) never talk to Kafka directly. A
   thin HTTP gateway is easier to secure, rate-limit and evolve, and real fobs/phones speak HTTP,
   not the Kafka protocol. It only checks that the body is JSON, then publishes and waits for
   Kafka's acknowledgement (`acks=all`, idempotent producer) before answering `202`.
2. **Kafka topic `device.status`** buffers the stream. If the status service is down or slow,
   events simply wait in Kafka; nothing is lost and devices are not blocked.
3. **status-service** consumes, validates, and writes to PostgreSQL. It is the single source of
   truth for "where is device X now".
4. **Dashboard** reads the REST API only.

## Kafka primer (what the prototype demonstrates)

| Concept | Here |
|---|---|
| **Topic** | A named, append-only log of messages: `device.status` (plus `device.status.dlq` for messages that failed every retry). |
| **Partition** | A topic is split into 6 partitions, each an independent ordered log. Partitions are the unit of parallelism. |
| **Key** | Every message is keyed by `deviceId`. Kafka hashes the key → the same device always goes to the same partition, so one device's events stay in order. Different devices spread across partitions. |
| **Consumer group** | `device-status-service`. Kafka gives each partition to exactly one consumer in the group. With `concurrency=3`, each thread owns 2 partitions; start a second instance and they rebalance to share them (max 6 useful consumers). |
| **Offset** | Position in a partition. The consumer commits offsets after processing, so after a restart it continues where it stopped. The dashboard's history shows `p<partition>@<offset>` for each event. |

Open Kafka UI (http://localhost:8090) → Topics → `device.status` to see messages per partition, and
Consumers → `device-status-service` to see lag while seeding.

*Kafka vs. AWS Kinesis:* Kinesis "streams/shards" ≈ Kafka "topics/partitions", "partition key" is the
same idea. Kinesis is managed (no brokers to run); Kafka is self-hosted (or MSK/Confluent) and runs
locally for free, which is why it is used here.

## Central status rules

Two tables: `device_event_history` (every unique event) and `device_latest_status` (one row per device).
Each event is applied in **one transaction with two SQL statements**:

```sql
-- 1. Dedup: UNIQUE (device_id, event_id). 0 rows inserted = duplicate → stop, change nothing.
INSERT INTO device_event_history (...) VALUES (...) ON CONFLICT (device_id, event_id) DO NOTHING;

-- 2. Conditional upsert: only replace the latest row with a NEWER reading.
INSERT INTO device_latest_status (...) VALUES (...)
ON CONFLICT (device_id) DO UPDATE SET ...
WHERE (EXCLUDED.captured_at, EXCLUDED.event_id) > (device_latest_status.captured_at, device_latest_status.event_id);
```

- **Ordering rule:** later `capturedAt` wins; on an exact tie the greater `eventId` (byte order,
  `COLLATE "C"`) wins. Deterministic regardless of arrival order.
- **Why it is safe:** the comparison happens inside PostgreSQL while it holds the row lock, not as
  "SELECT, compare in Java, UPDATE" (which two threads could interleave). Partitioning by key already
  serialises one device's events, but correctness does not depend on it: the concurrency test applies
  250 shuffled events from 8 threads and the newest still wins.
- **Late events** are still stored in history (they really happened) but do not touch the latest status.

## Error handling and retries

| Problem | Behaviour |
|---|---|
| Malformed JSON, missing/invalid fields, impossible lat/lon/battery, timestamp > 5 min in the future, unknown status | Not retried. Saved to `device_event_rejected` with the reason and raw payload, logged as `WARN REJECTED ...`, offset committed, consumer continues. |
| Unexpected failure (e.g. PostgreSQL down) | Spring Kafka `DefaultErrorHandler` retries the same record with exponential back-off (1 s, 2 s, 4 s, 8 s, 10 s). After the last attempt the record is published to `device.status.dlq` and the consumer moves on. |
| Consumer crash / restart | Offsets are committed only after processing → **at-least-once** delivery. Re-delivered events are harmless because storage is idempotent (dedup + conditional upsert). |
| Kafka down when a device posts | ingest-api returns `503`; the device retries later. |

## Known limitations / future improvements

- **Scale:** one Kafka broker, replication factor 1, one PostgreSQL. Production would use 3+ brokers
  (RF=3), more partitions, several status-service instances, and batch inserts (`@KafkaListener` batch
  mode + multi-row upsert) for higher throughput. History would be partitioned by time (or moved to a
  time-series/columnar store) with a retention policy.
- **DLQ** messages are kept but not automatically replayed; there is no admin tool for that yet.
- **Validation lives in the consumer** (central, as required). Validating at the ingest edge as well
  would give devices immediate `400` feedback.
- **No authentication** of devices or dashboard users; a real system needs per-device credentials
  and user → device authorisation.
- The dashboard polls every 3 s; WebSockets/SSE would push updates instead. No map view.
- In-memory "processed since startup" counters reset on restart; Micrometer/Prometheus would replace them.
