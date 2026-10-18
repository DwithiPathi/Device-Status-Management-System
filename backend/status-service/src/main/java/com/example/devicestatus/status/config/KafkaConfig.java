package com.example.devicestatus.status.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    /** Main topic, keyed by deviceId. Created on startup if missing (broker auto-create is off). */
    @Bean
    public NewTopic deviceStatusTopic(AppProperties props) {
        AppProperties.Kafka k = props.kafka();
        return TopicBuilder.name(k.topic()).partitions(k.partitions()).replicas(k.replicationFactor()).build();
    }

    /** Dead-letter topic: same partition count, so a record keeps its partition number there. */
    @Bean
    public NewTopic deviceStatusDlqTopic(AppProperties props) {
        AppProperties.Kafka k = props.kafka();
        return TopicBuilder.name(k.dlqTopic()).partitions(k.partitions()).replicas(k.replicationFactor()).build();
    }

    /**
     * Invalid events never throw (they are stored as rejected), so this only handles unexpected
     * failures such as PostgreSQL being down: retry the same record with exponential back-off, then
     * publish it to the DLQ and move on so one poisoned record cannot block the partition forever.
     * Spring Boot plugs this bean into the listener container factory automatically.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, AppProperties props) {
        AppProperties.Kafka k = props.kafka();
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template, (record, ex) -> {
            log.error("DEAD_LETTERED partition={} offset={} after {} retries: {}",
                    record.partition(), record.offset(), k.retry().maxAttempts(), ex.getMessage());
            return new TopicPartition(k.dlqTopic(), record.partition());
        });
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(k.retry().maxAttempts());
        backOff.setInitialInterval(k.retry().initialInterval().toMillis());
        backOff.setMultiplier(k.retry().multiplier());
        backOff.setMaxInterval(k.retry().maxInterval().toMillis());
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
