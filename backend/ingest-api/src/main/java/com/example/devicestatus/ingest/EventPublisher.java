package com.example.devicestatus.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes raw device events to Kafka.
 *
 * <p>The message key is the event's {@code deviceId}. Kafka hashes the key to pick a partition, so
 * every event of one device lands in the same partition and is consumed in the order it was sent.
 * Events without a usable deviceId are still forwarded (key = null, any partition) so that the
 * status-service can reject and record them with a reason.
 */
@Service
public class EventPublisher {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final String topic;
    private final Duration sendTimeout;

    public EventPublisher(KafkaTemplate<String, String> kafka,
                          ObjectMapper mapper,
                          @Value("${app.kafka.topic}") String topic,
                          @Value("${app.kafka.send-timeout-ms}") long sendTimeoutMs) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.topic = topic;
        this.sendTimeout = Duration.ofMillis(sendTimeoutMs);
    }

    /** Sends all events and waits until Kafka has acknowledged every one of them. */
    public void publishAll(List<JsonNode> events) {
        List<CompletableFuture<?>> pending = new ArrayList<>(events.size());
        try {
            for (JsonNode event : events) {
                pending.add(kafka.send(topic, keyOf(event), toJson(event)));
            }
            CompletableFuture.allOf(pending.toArray(new CompletableFuture<?>[0]))
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaUnavailableException("Interrupted while waiting for Kafka", e);
        } catch (ExecutionException e) {
            throw new KafkaUnavailableException("Kafka rejected the events: " + e.getCause().getMessage(), e.getCause());
        } catch (TimeoutException e) {
            throw new KafkaUnavailableException("Kafka did not acknowledge within " + sendTimeout.toSeconds() + " s", e);
        } catch (RuntimeException e) {
            // KafkaTemplate.send throws synchronously when the broker cannot be reached (max.block.ms).
            throw new KafkaUnavailableException("Kafka is not reachable: " + e.getMessage(), e);
        }
    }

    static String keyOf(JsonNode event) {
        JsonNode deviceId = event == null ? null : event.get("deviceId");
        if (deviceId != null && deviceId.isTextual() && !deviceId.asText().isBlank()) {
            return deviceId.asText();
        }
        return null;
    }

    private String toJson(JsonNode event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise event", e);
        }
    }
}
