package com.example.devicestatus.status.processing;

import com.example.devicestatus.status.event.DeviceEvent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The central status rules. Each event is applied in one transaction with two SQL statements:
 *
 * <ol>
 *   <li><b>Dedup</b> - insert into history; the UNIQUE (device_id, event_id) constraint makes a
 *       re-sent or re-delivered event insert 0 rows, and then nothing else changes.</li>
 *   <li><b>Conditional upsert</b> - replace the device's latest-status row only if this reading is
 *       newer: later {@code captured_at} wins, an exact tie is broken by the greater {@code event_id}.
 *       The comparison runs inside PostgreSQL while it holds the row lock, so concurrent consumers can
 *       never overwrite a newer reading with an older one.</li>
 * </ol>
 */
@Service
public class DeviceEventProcessor {

    private static final String INSERT_HISTORY = """
            INSERT INTO device_event_history
                (device_id, event_id, captured_at, latitude, longitude, battery_percent, status, kafka_partition, kafka_offset)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (device_id, event_id) DO NOTHING
            """;

    private static final String UPSERT_LATEST = """
            INSERT INTO device_latest_status AS cur
                (device_id, event_id, captured_at, latitude, longitude, battery_percent, status, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (device_id) DO UPDATE SET
                event_id        = EXCLUDED.event_id,
                captured_at     = EXCLUDED.captured_at,
                latitude        = EXCLUDED.latitude,
                longitude       = EXCLUDED.longitude,
                battery_percent = EXCLUDED.battery_percent,
                status          = EXCLUDED.status,
                updated_at      = now()
            WHERE (EXCLUDED.captured_at, EXCLUDED.event_id) > (cur.captured_at, cur.event_id)
            """;

    private static final String INSERT_REJECTED = """
            INSERT INTO device_event_rejected (device_id, event_id, reason, payload, kafka_partition, kafka_offset)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final int MAX_STORED_PAYLOAD = 4000;

    private final JdbcTemplate jdbc;

    public DeviceEventProcessor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Outcome apply(DeviceEvent e, Integer kafkaPartition, Long kafkaOffset) {
        OffsetDateTime capturedAt = utc(e.capturedAt());
        int inserted = jdbc.update(INSERT_HISTORY,
                e.deviceId(), e.eventId(), capturedAt, e.latitude(), e.longitude(), e.batteryPercent(),
                e.status().name(), kafkaPartition, kafkaOffset);
        if (inserted == 0) {
            return Outcome.DUPLICATE_IGNORED;
        }
        int changed = jdbc.update(UPSERT_LATEST,
                e.deviceId(), e.eventId(), capturedAt, e.latitude(), e.longitude(), e.batteryPercent(), e.status().name());
        return changed == 1 ? Outcome.LATEST_UPDATED : Outcome.STORED_OLDER_EVENT;
    }

    public void reject(String deviceId, String eventId, String reason, String payload,
                       Integer kafkaPartition, Long kafkaOffset) {
        String stored = payload != null && payload.length() > MAX_STORED_PAYLOAD
                ? payload.substring(0, MAX_STORED_PAYLOAD) + "…"
                : payload;
        jdbc.update(INSERT_REJECTED, deviceId, eventId, reason, stored, kafkaPartition, kafkaOffset);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
