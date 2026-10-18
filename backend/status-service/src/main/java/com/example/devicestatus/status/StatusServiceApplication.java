package com.example.devicestatus.status;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Status service: the single source of truth for "where is device X now".
 *
 * <p>Consumes {@code device.status} from Kafka, validates each event, stores it in history
 * (ignoring duplicates) and moves the device's latest-status row forward only for a newer reading.
 * Serves the REST API used by the dashboard.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class StatusServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(StatusServiceApplication.class, args);
    }
}
