package com.example.devicestatus.status.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** JSON shapes returned by the REST API (consumed by the React dashboard and the simulator). */
public final class ApiModels {

    private ApiModels() {
    }

    public record DeviceStatus(String deviceId,
                               String eventId,
                               Instant capturedAt,
                               double latitude,
                               double longitude,
                               int batteryPercent,
                               String status,
                               Instant updatedAt) {
    }

    public record DevicePage(List<DeviceStatus> items, int page, int size, long total) {
    }

    public record HistoryEvent(String eventId,
                               Instant capturedAt,
                               double latitude,
                               double longitude,
                               int batteryPercent,
                               String status,
                               Integer kafkaPartition,
                               Long kafkaOffset,
                               Instant receivedAt) {
    }

    public record DeviceHistory(String deviceId, long totalEvents, List<HistoryEvent> events) {
    }

    public record RejectedEvent(long id,
                                String deviceId,
                                String eventId,
                                String reason,
                                String payload,
                                Integer kafkaPartition,
                                Long kafkaOffset,
                                Instant rejectedAt) {
    }

    public record Stats(long devices, long historyEvents, long rejectedEvents, Map<String, Long> processedSinceStartup) {
    }

    public record Health(String status, String database, String kafkaConsumer, List<Integer> assignedPartitions) {
    }
}
