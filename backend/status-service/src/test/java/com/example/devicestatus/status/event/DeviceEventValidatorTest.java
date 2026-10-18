package com.example.devicestatus.status.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Plain unit test, no Spring, no Docker. */
class DeviceEventValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    private final DeviceEventValidator validator = new DeviceEventValidator(
            new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofMinutes(5));

    private static String event(String overrides) {
        String base = "\"eventId\":\"e-1\",\"deviceId\":\"device-001\",\"capturedAt\":\"2026-10-16T11:59:00Z\","
                + "\"latitude\":34.1,\"longitude\":-118.2,\"batteryPercent\":80,\"status\":\"ACTIVE\"";
        return "{" + base + (overrides.isEmpty() ? "" : "," + overrides) + "}";
    }

    @Test
    void acceptsValidEvent() {
        ValidationResult r = validator.validate(event(""));
        assertTrue(r.isValid(), r.reason());
        assertEquals("device-001", r.event().deviceId());
        assertEquals(Instant.parse("2026-10-16T11:59:00Z"), r.event().capturedAt());
        assertEquals(DeviceState.ACTIVE, r.event().status());
    }

    @Test
    void acceptsTimestampWithOffsetAndBoundaryValues() {
        ValidationResult r = validator.validate(event(
                "\"capturedAt\":\"2026-10-16T13:59:00+02:00\",\"latitude\":-90,\"longitude\":180,\"batteryPercent\":0"));
        assertTrue(r.isValid(), r.reason());
        assertEquals(Instant.parse("2026-10-16T11:59:00Z"), r.event().capturedAt());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "\"latitude\":999                   | latitude 999 is outside -90..90",
            "\"latitude\":-90.5                 | latitude -90.5 is outside -90..90",
            "\"longitude\":-500                 | longitude -500 is outside -180..180",
            "\"latitude\":\"34.1\"              | latitude must be a number",
            "\"batteryPercent\":150             | batteryPercent 150 is outside 0..100",
            "\"batteryPercent\":-1              | batteryPercent -1 is outside 0..100",
            "\"batteryPercent\":50.5            | batteryPercent must be a whole number",
            "\"capturedAt\":\"not-a-timestamp\" | capturedAt \"not-a-timestamp\" is not an ISO-8601 timestamp",
            "\"capturedAt\":\"2026-10-16T12:06:00Z\" | is more than 5 min in the future",
            "\"status\":\"TELEPORTING\"         | status \"TELEPORTING\" is not one of ACTIVE, IDLE, LOW_BATTERY, OFFLINE",
            "\"deviceId\":\"\"                  | deviceId is missing",
            "\"deviceId\":\"bad id with spaces\" | deviceId must be 1-128 characters",
            "\"eventId\":null                   | eventId is missing",
            "\"deviceId\":42                    | deviceId is missing",
    })
    void rejectsInvalidField(String override, String expectedReason) {
        ValidationResult r = validator.validate(event(override));
        assertFalse(r.isValid());
        assertTrue(r.reason().contains(expectedReason), () -> "reason was: " + r.reason());
    }

    @Test
    void acceptsSimulatorStyleEventIds() {
        assertTrue(validator.validate(event("\"eventId\":\"demo-abc-t+60\"")).isValid());
        assertFalse(validator.validate(event("\"eventId\":\"has space\"")).isValid());
    }

    @Test
    void allowsSmallClockSkew() {
        assertTrue(validator.validate(event("\"capturedAt\":\"2026-10-16T12:04:59Z\"")).isValid());
    }

    @Test
    void missingDeviceIdIsRejectedButEventIdIsKept() {
        ValidationResult r = validator.validate("{\"eventId\":\"e-9\",\"capturedAt\":\"2026-10-16T11:59:00Z\","
                + "\"latitude\":1,\"longitude\":1,\"batteryPercent\":5,\"status\":\"IDLE\"}");
        assertFalse(r.isValid());
        assertNull(r.deviceId());
        assertEquals("e-9", r.eventId());
        assertEquals("deviceId is missing", r.reason());
    }

    @Test
    void reportsEveryProblemAtOnce() {
        ValidationResult r = validator.validate(event("\"latitude\":999,\"batteryPercent\":150"));
        assertFalse(r.isValid());
        assertEquals("latitude 999 is outside -90..90; batteryPercent 150 is outside 0..100", r.reason());
        assertEquals("device-001", r.deviceId());
    }

    @Test
    void rejectsMissingFields() {
        ValidationResult r = validator.validate("{}");
        assertFalse(r.isValid());
        assertEquals("deviceId is missing; eventId is missing; capturedAt is missing; latitude is missing; "
                + "longitude is missing; batteryPercent is missing; status is missing", r.reason());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{not json          | Malformed JSON",
            "[1,2,3]            | Message is not a JSON object",
            "'   '              | Empty message",
    })
    void rejectsBrokenPayload(String payload, String expectedReason) {
        ValidationResult r = validator.validate(payload);
        assertFalse(r.isValid());
        assertEquals(expectedReason, r.reason());
    }

    @Test
    void rejectsNullPayload() {
        assertEquals("Empty message", validator.validate(null).reason());
    }
}
