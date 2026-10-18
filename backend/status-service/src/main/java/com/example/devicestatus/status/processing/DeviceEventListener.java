package com.example.devicestatus.status.processing;

import com.example.devicestatus.status.event.DeviceEvent;
import com.example.devicestatus.status.event.DeviceEventValidator;
import com.example.devicestatus.status.event.ValidationResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka consumer for {@code device.status}.
 *
 * <p>Consumer group {@code device-status-service}: Kafka hands each partition to exactly one consumer
 * thread in the group, so one device's events (same key, same partition) are processed in order.
 * Offsets are committed after processing (at-least-once); re-delivered events are harmless because
 * {@link DeviceEventProcessor} is idempotent.
 */
@Component
public class DeviceEventListener {

    public static final String LISTENER_ID = "device-status-listener";

    private static final Logger log = LoggerFactory.getLogger(DeviceEventListener.class);

    private final DeviceEventValidator validator;
    private final DeviceEventProcessor processor;
    private final OutcomeCounters counters;

    public DeviceEventListener(DeviceEventValidator validator, DeviceEventProcessor processor, OutcomeCounters counters) {
        this.validator = validator;
        this.processor = processor;
        this.counters = counters;
    }

    @KafkaListener(id = LISTENER_ID,
            topics = "${app.kafka.topic}",
            groupId = "${spring.kafka.consumer.group-id}",
            concurrency = "${app.kafka.consumer-concurrency}")
    public void onMessage(ConsumerRecord<String, String> record) {
        ValidationResult result = validator.validate(record.value());

        if (!result.isValid()) {
            processor.reject(result.deviceId(), result.eventId(), result.reason(), record.value(),
                    record.partition(), record.offset());
            counters.record(Outcome.REJECTED);
            log.warn("REJECTED p{}@{} deviceId={} eventId={} reason={}",
                    record.partition(), record.offset(), result.deviceId(), result.eventId(), result.reason());
            return;
        }

        DeviceEvent event = result.event();
        Outcome outcome = processor.apply(event, record.partition(), record.offset());
        counters.record(outcome);
        log.info("{} p{}@{} deviceId={} eventId={} capturedAt={} lat={} lon={}", outcome,
                record.partition(), record.offset(), event.deviceId(), event.eventId(), event.capturedAt(),
                event.latitude(), event.longitude());
    }
}
