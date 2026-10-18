package com.example.devicestatus.ingest;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ingest API: the HTTP front door for devices.
 *
 * <p>Devices (here: the simulator) never talk to Kafka directly. They POST JSON to this service,
 * which publishes each event to the {@code device.status} topic keyed by {@code deviceId} and answers
 * {@code 202 Accepted} once Kafka has acknowledged it. It deliberately does not validate the fields:
 * validation is the central status-service's job, so bad readings are recorded there with a reason.
 */
@SpringBootApplication
public class IngestApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestApiApplication.class, args);
    }
}
