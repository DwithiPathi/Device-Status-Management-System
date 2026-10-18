package com.example.devicestatus.ingest;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the topic so it exists with an explicit partition count (the broker has
 * auto-create switched off). Spring's KafkaAdmin creates it on startup if it is missing.
 */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic deviceStatusTopic(@Value("${app.kafka.topic}") String topic,
                                      @Value("${app.kafka.partitions}") int partitions,
                                      @Value("${app.kafka.replication-factor}") int replicationFactor) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(replicationFactor).build();
    }
}
