package com.example.devicestatus.status.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.devicestatus.status.api.DeviceQueryRepository;
import com.example.devicestatus.status.event.DeviceEvent;
import com.example.devicestatus.status.event.DeviceState;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The central status rules against a real PostgreSQL (Testcontainers). Kafka is not started here;
 * see {@code KafkaPipelineTest} for the full loop. Skipped automatically when Docker is not running.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false",
        "spring.kafka.bootstrap-servers=localhost:1"
})
@Testcontainers(disabledWithoutDocker = true)
class DeviceEventProcessorTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Instant T0 = Instant.parse("2026-10-16T12:00:00Z");

    @Autowired
    private DeviceEventProcessor processor;

    @Autowired
    private DeviceQueryRepository queries;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE device_event_history, device_latest_status, device_event_rejected");
    }

    private static DeviceEvent event(String deviceId, String eventId, int secondsAfterT0, double lat) {
        return new DeviceEvent(eventId, deviceId, T0.plusSeconds(secondsAfterT0), lat, -118.25, 80, DeviceState.ACTIVE);
    }

    private String latestEventId(String deviceId) {
        return queries.device(deviceId).orElseThrow().eventId();
    }

    @Test
    void normalFlowKeepsEveryEventAndTheNewestAsLatest() {
        for (int d = 1; d <= 10; d++) {
            for (int s = 1; s <= 20; s++) {
                processor.apply(event("device-" + d, "d" + d + "-e" + String.format("%02d", s), s * 30, 34 + s * 0.001), 0, (long) s);
            }
        }
        assertEquals(200, queries.count("device_event_history"));
        assertEquals(10, queries.count("device_latest_status"));
        for (int d = 1; d <= 10; d++) {
            assertEquals("d" + d + "-e20", latestEventId("device-" + d));
        }
    }

    @Test
    void duplicateIsIgnored() {
        DeviceEvent e = event("dev-dup", "e-1", 0, 34.0);
        assertEquals(Outcome.LATEST_UPDATED, processor.apply(e, 0, 1L));
        assertEquals(Outcome.DUPLICATE_IGNORED, processor.apply(e, 0, 2L));
        assertEquals(Outcome.DUPLICATE_IGNORED, processor.apply(e, 0, 3L));
        assertEquals(1, queries.history("dev-dup", 10).totalEvents());
    }

    @Test
    void lateEventGoesToHistoryOnly() {
        assertEquals(Outcome.LATEST_UPDATED, processor.apply(event("dev-late", "e-new", 60, 34.2), 0, 1L));
        assertEquals(Outcome.STORED_OLDER_EVENT, processor.apply(event("dev-late", "e-old", -60, 34.1), 0, 2L));
        assertEquals("e-new", latestEventId("dev-late"));
        assertEquals(34.2, queries.device("dev-late").orElseThrow().latitude());
        assertEquals(2, queries.history("dev-late", 10).totalEvents());
        assertEquals("e-new", queries.history("dev-late", 10).events().get(0).eventId());
    }

    @Test
    void equalTimestampTieIsBrokenByGreaterEventId() {
        processor.apply(event("dev-tie", "e-b", 0, 1), 0, 1L);
        assertEquals(Outcome.STORED_OLDER_EVENT, processor.apply(event("dev-tie", "e-a", 0, 2), 0, 2L));
        assertEquals(Outcome.LATEST_UPDATED, processor.apply(event("dev-tie", "e-c", 0, 3), 0, 3L));
        assertEquals("e-c", latestEventId("dev-tie"));
    }

    @Test
    void rejectedEventsAreRecorded() {
        processor.reject(null, "e-x", "deviceId is missing", "{\"eventId\":\"e-x\"}", 2, 7L);
        assertEquals(1, queries.count("device_event_rejected"));
        assertEquals("deviceId is missing", queries.rejected(5).get(0).reason());
        assertEquals(0, queries.count("device_latest_status"));
    }

    @Test
    void concurrentShuffledEventsStillEndWithTheNewest() throws Exception {
        List<DeviceEvent> events = new ArrayList<>();
        for (int d = 1; d <= 5; d++) {
            for (int s = 1; s <= 50; s++) {
                events.add(event("dev-c" + d, "c" + d + "-" + String.format("%03d", s), s, s));
            }
        }
        Collections.shuffle(events, new Random(42));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (DeviceEvent e : events) {
                futures.add(pool.submit(() -> processor.apply(e, null, null)));
            }
            for (Future<Outcome> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        assertEquals(250, queries.count("device_event_history"));
        for (int d = 1; d <= 5; d++) {
            assertEquals("c" + d + "-050", latestEventId("dev-c" + d));
        }
    }
}
