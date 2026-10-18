package com.example.devicestatus.ingest;

/** Kafka did not acknowledge the events in time; the device should retry later (HTTP 503). */
public class KafkaUnavailableException extends RuntimeException {

    public KafkaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
