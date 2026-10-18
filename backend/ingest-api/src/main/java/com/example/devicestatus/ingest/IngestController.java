package com.example.devicestatus.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * POST /api/events         one JSON event          -> 202 {"accepted": 1}
 * POST /api/events/batch   JSON array (max 1,000)   -> 202 {"accepted": n}
 * GET  /api/health                                   -> 200 {"status": "UP"}
 * </pre>
 * 400 if the body is not JSON of the right shape, 503 if Kafka does not acknowledge.
 */
@RestController
@RequestMapping("/api")
public class IngestController {

    public static final int MAX_BATCH_SIZE = 1000;

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final EventPublisher publisher;

    public IngestController(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> ingestOne(@RequestBody JsonNode body) {
        if (body == null || !body.isObject()) {
            return badRequest("Body must be a single JSON object. Use /api/events/batch for an array.");
        }
        publisher.publishAll(List.of(body));
        return ResponseEntity.accepted().body(Map.of("accepted", 1));
    }

    @PostMapping("/events/batch")
    public ResponseEntity<Map<String, Object>> ingestBatch(@RequestBody JsonNode body) {
        if (body == null || !body.isArray()) {
            return badRequest("Body must be a JSON array of events.");
        }
        if (body.isEmpty()) {
            return badRequest("Batch is empty.");
        }
        if (body.size() > MAX_BATCH_SIZE) {
            return badRequest("Batch has " + body.size() + " events; the maximum is " + MAX_BATCH_SIZE + ".");
        }
        List<JsonNode> events = new ArrayList<>(body.size());
        body.forEach(events::add);
        publisher.publishAll(events);
        return ResponseEntity.accepted().body(Map.of("accepted", events.size()));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "ingest-api");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> notJson(HttpMessageNotReadableException e) {
        return badRequest("Request body is not valid JSON.");
    }

    @ExceptionHandler(KafkaUnavailableException.class)
    public ResponseEntity<Map<String, Object>> kafkaDown(KafkaUnavailableException e) {
        log.warn("Kafka unavailable, returning 503: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Kafka is unavailable, please retry later.", "detail", String.valueOf(e.getMessage())));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
