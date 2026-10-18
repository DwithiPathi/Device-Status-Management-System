package com.example.devicestatus.status.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Everything under {@code app:} in application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Kafka kafka, Validation validation) {

    public record Kafka(String topic,
                        int partitions,
                        int replicationFactor,
                        String dlqTopic,
                        int consumerConcurrency,
                        Retry retry) {
    }

    /** Back-off for unexpected failures (e.g. database down) before a record goes to the DLQ. */
    public record Retry(int maxAttempts, Duration initialInterval, double multiplier, Duration maxInterval) {
    }

    public record Validation(Duration maxFutureSkew) {
    }
}
