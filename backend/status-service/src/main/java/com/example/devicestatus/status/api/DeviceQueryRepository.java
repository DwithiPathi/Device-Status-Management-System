package com.example.devicestatus.status.api;

import com.example.devicestatus.status.api.ApiModels.DeviceHistory;
import com.example.devicestatus.status.api.ApiModels.DevicePage;
import com.example.devicestatus.status.api.ApiModels.DeviceStatus;
import com.example.devicestatus.status.api.ApiModels.HistoryEvent;
import com.example.devicestatus.status.api.ApiModels.RejectedEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Read-only queries for the REST API. */
@Repository
public class DeviceQueryRepository {

    private static final String LATEST_COLUMNS =
            "device_id, event_id, captured_at, latitude, longitude, battery_percent, status, updated_at";

    private static final RowMapper<DeviceStatus> DEVICE_STATUS = (rs, i) -> new DeviceStatus(
            rs.getString("device_id"),
            rs.getString("event_id"),
            instant(rs, "captured_at"),
            rs.getDouble("latitude"),
            rs.getDouble("longitude"),
            rs.getInt("battery_percent"),
            rs.getString("status"),
            instant(rs, "updated_at"));

    private static final RowMapper<HistoryEvent> HISTORY_EVENT = (rs, i) -> new HistoryEvent(
            rs.getString("event_id"),
            instant(rs, "captured_at"),
            rs.getDouble("latitude"),
            rs.getDouble("longitude"),
            rs.getInt("battery_percent"),
            rs.getString("status"),
            rs.getObject("kafka_partition", Integer.class),
            rs.getObject("kafka_offset", Long.class),
            instant(rs, "received_at"));

    private static final RowMapper<RejectedEvent> REJECTED_EVENT = (rs, i) -> new RejectedEvent(
            rs.getLong("id"),
            rs.getString("device_id"),
            rs.getString("event_id"),
            rs.getString("reason"),
            rs.getString("payload"),
            rs.getObject("kafka_partition", Integer.class),
            rs.getObject("kafka_offset", Long.class),
            instant(rs, "rejected_at"));

    private final JdbcTemplate jdbc;

    public DeviceQueryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Latest status of every device, ordered by deviceId, optionally filtered by a case-insensitive substring. */
    public DevicePage devices(String search, int page, int size) {
        String pattern = "%" + escapeLike(search == null ? "" : search.trim()) + "%";
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM device_latest_status WHERE device_id ILIKE ? ESCAPE '\\'", Long.class, pattern);
        List<DeviceStatus> items = jdbc.query(
                "SELECT " + LATEST_COLUMNS + " FROM device_latest_status WHERE device_id ILIKE ? ESCAPE '\\'"
                        + " ORDER BY device_id LIMIT ? OFFSET ?",
                DEVICE_STATUS, pattern, size, (long) page * size);
        return new DevicePage(items, page, size, total == null ? 0 : total);
    }

    public Optional<DeviceStatus> device(String deviceId) {
        return jdbc.query("SELECT " + LATEST_COLUMNS + " FROM device_latest_status WHERE device_id = ?",
                DEVICE_STATUS, deviceId).stream().findFirst();
    }

    /** Most recent unique events of one device, newest reading first. */
    public DeviceHistory history(String deviceId, int limit) {
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM device_event_history WHERE device_id = ?", Long.class, deviceId);
        List<HistoryEvent> events = jdbc.query("""
                SELECT event_id, captured_at, latitude, longitude, battery_percent, status,
                       kafka_partition, kafka_offset, received_at
                FROM device_event_history
                WHERE device_id = ?
                ORDER BY captured_at DESC, event_id DESC
                LIMIT ?
                """, HISTORY_EVENT, deviceId, limit);
        return new DeviceHistory(deviceId, total == null ? 0 : total, events);
    }

    public List<RejectedEvent> rejected(int limit) {
        return jdbc.query("""
                SELECT id, device_id, event_id, reason, payload, kafka_partition, kafka_offset, rejected_at
                FROM device_event_rejected
                ORDER BY id DESC
                LIMIT ?
                """, REJECTED_EVENT, limit);
    }

    public long count(String table) {
        if (!List.of("device_latest_status", "device_event_history", "device_event_rejected").contains(table)) {
            throw new IllegalArgumentException("Unknown table " + table);
        }
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return n == null ? 0 : n;
    }

    public boolean databaseUp() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
