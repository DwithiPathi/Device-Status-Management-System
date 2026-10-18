package com.example.devicestatus.status.api;

import com.example.devicestatus.status.api.ApiModels.DeviceHistory;
import com.example.devicestatus.status.api.ApiModels.DevicePage;
import com.example.devicestatus.status.api.ApiModels.DeviceStatus;
import com.example.devicestatus.status.api.ApiModels.Health;
import com.example.devicestatus.status.api.ApiModels.RejectedEvent;
import com.example.devicestatus.status.api.ApiModels.Stats;
import com.example.devicestatus.status.processing.DeviceEventListener;
import com.example.devicestatus.status.processing.OutcomeCounters;
import java.util.Collection;
import java.util.List;
import org.apache.kafka.common.TopicPartition;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * GET /api/devices?search=&amp;page=0&amp;size=25     latest status of every device (paginated)
 * GET /api/devices/{deviceId}                    latest status of one device, 404 if unknown
 * GET /api/devices/{deviceId}/history?limit=20   recent unique events, newest first
 * GET /api/rejected-events?limit=20              recently rejected messages and why
 * GET /api/stats                                 row counts + outcomes since startup
 * GET /api/health                                database + Kafka consumer state
 * </pre>
 */
@RestController
@RequestMapping("/api")
public class DeviceController {

    private final DeviceQueryRepository repository;
    private final OutcomeCounters counters;
    private final KafkaListenerEndpointRegistry listeners;

    public DeviceController(DeviceQueryRepository repository, OutcomeCounters counters,
                            KafkaListenerEndpointRegistry listeners) {
        this.repository = repository;
        this.counters = counters;
        this.listeners = listeners;
    }

    @GetMapping("/devices")
    public DevicePage devices(@RequestParam(defaultValue = "") String search,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "25") int size) {
        return repository.devices(search, Math.max(0, page), clamp(size, 1, 200));
    }

    @GetMapping("/devices/{deviceId}")
    public ResponseEntity<DeviceStatus> device(@PathVariable String deviceId) {
        return ResponseEntity.of(repository.device(deviceId));
    }

    @GetMapping("/devices/{deviceId}/history")
    public DeviceHistory history(@PathVariable String deviceId, @RequestParam(defaultValue = "20") int limit) {
        return repository.history(deviceId, clamp(limit, 1, 500));
    }

    @GetMapping("/rejected-events")
    public List<RejectedEvent> rejected(@RequestParam(defaultValue = "20") int limit) {
        return repository.rejected(clamp(limit, 1, 500));
    }

    @GetMapping("/stats")
    public Stats stats() {
        return new Stats(
                repository.count("device_latest_status"),
                repository.count("device_event_history"),
                repository.count("device_event_rejected"),
                counters.snapshot());
    }

    @GetMapping("/health")
    public Health health() {
        boolean dbUp = repository.databaseUp();
        MessageListenerContainer container = listeners.getListenerContainer(DeviceEventListener.LISTENER_ID);
        boolean consumerRunning = container != null && container.isRunning();
        List<Integer> partitions = List.of();
        if (container != null) {
            Collection<TopicPartition> assigned = container.getAssignedPartitions();
            if (assigned != null) {
                partitions = assigned.stream().map(TopicPartition::partition).sorted().toList();
            }
        }
        return new Health(
                dbUp && consumerRunning ? "UP" : "DEGRADED",
                dbUp ? "UP" : "DOWN",
                container == null ? "NOT_REGISTERED" : consumerRunning ? "RUNNING" : "STOPPED",
                partitions);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
