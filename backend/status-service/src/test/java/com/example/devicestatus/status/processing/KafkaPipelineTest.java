package com.example.devicestatus.status.processing;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.devicestatus.status.api.DeviceQueryRepository;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Full loop through a real Kafka broker and PostgreSQL. Skipped automatically when Docker is not running. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class KafkaPipelineTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    @SuppressWarnings("deprecation")
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @Autowired
    private KafkaTemplate<String, String> template;

    @Autowired
    private DeviceQueryRepository queries;

    private static String event(String eventId, String capturedAt, double lat) {
        return "{\"eventId\":\"" + eventId + "\",\"deviceId\":\"pipe-1\",\"capturedAt\":\"" + capturedAt + "\","
                + "\"latitude\":" + lat + ",\"longitude\":-118.25,\"batteryPercent\":70,\"status\":\"ACTIVE\"}";
    }

    @Test
    void duplicateLateAndInvalidMessagesThroughKafka() throws Exception {
        String newer = event("p-2", "2026-10-16T12:05:00Z", 34.2);
        String older = event("p-1", "2026-10-16T12:00:00Z", 34.1);
        String invalid = event("p-3", "2026-10-16T12:10:00Z", 999);

        template.send("device.status", "pipe-1", newer).get();
        template.send("device.status", "pipe-1", newer).get();   // duplicate
        template.send("device.status", "pipe-1", older).get();   // late
        template.send("device.status", "pipe-1", invalid).get(); // rejected
        template.send("device.status", null, "{broken").get();   // malformed JSON, no key

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            assertEquals(2, queries.history("pipe-1", 10).totalEvents());
            assertEquals(2, queries.count("device_event_rejected"));
        });
        assertEquals(Optional.of("p-2"), queries.device("pipe-1").map(d -> d.eventId()));
        assertEquals(34.2, queries.device("pipe-1").orElseThrow().latitude());
    }
}
