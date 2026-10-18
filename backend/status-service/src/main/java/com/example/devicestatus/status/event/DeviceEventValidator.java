package com.example.devicestatus.status.event;

import com.example.devicestatus.status.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Turns a raw Kafka message into a {@link DeviceEvent}, or explains why it cannot be one.
 * All problems found are reported together, e.g. {@code "latitude 999.0 is outside -90..90; batteryPercent 150 is outside 0..100"}.
 */
@Component
public class DeviceEventValidator {

    /** Device IDs appear in URLs (/api/devices/{deviceId}), so they are restricted to URL-safe characters. */
    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9._:\\-]{1,128}");
    /** Event IDs only need to be unique per device: any visible ASCII, no spaces. */
    private static final Pattern EVENT_ID = Pattern.compile("[\\x21-\\x7E]{1,128}");
    private static final String STATES = Arrays.stream(DeviceState.values()).map(Enum::name).collect(Collectors.joining(", "));

    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration maxFutureSkew;

    @Autowired
    public DeviceEventValidator(ObjectMapper mapper, Clock clock, AppProperties props) {
        this(mapper, clock, props.validation().maxFutureSkew());
    }

    public DeviceEventValidator(ObjectMapper mapper, Clock clock, Duration maxFutureSkew) {
        this.mapper = mapper;
        this.clock = clock;
        this.maxFutureSkew = maxFutureSkew;
    }

    public ValidationResult validate(String payload) {
        if (payload == null || payload.isBlank()) {
            return ValidationResult.invalid(null, null, "Empty message");
        }
        JsonNode root;
        try {
            root = mapper.readTree(payload);
        } catch (Exception e) {
            return ValidationResult.invalid(null, null, "Malformed JSON");
        }
        if (root == null || !root.isObject()) {
            return ValidationResult.invalid(null, null, "Message is not a JSON object");
        }

        List<String> problems = new ArrayList<>();
        String deviceId = text(root, "deviceId");
        String eventId = text(root, "eventId");
        if (deviceId == null) {
            problems.add("deviceId is missing");
        } else if (!DEVICE_ID.matcher(deviceId).matches()) {
            problems.add("deviceId must be 1-128 characters of letters, digits, '.', '_', ':' or '-'");
        }
        if (eventId == null) {
            problems.add("eventId is missing");
        } else if (!EVENT_ID.matcher(eventId).matches()) {
            problems.add("eventId must be 1-128 visible characters without spaces");
        }

        Instant capturedAt = null;
        String rawTime = text(root, "capturedAt");
        if (rawTime == null) {
            problems.add("capturedAt is missing");
        } else {
            try {
                capturedAt = Instant.parse(rawTime);
                if (capturedAt.isAfter(clock.instant().plus(maxFutureSkew))) {
                    problems.add("capturedAt " + rawTime + " is more than " + human(maxFutureSkew) + " in the future");
                }
            } catch (DateTimeParseException e) {
                problems.add("capturedAt \"" + shorten(rawTime) + "\" is not an ISO-8601 timestamp");
            }
        }

        Double latitude = number(root, "latitude", -90, 90, problems);
        Double longitude = number(root, "longitude", -180, 180, problems);
        Integer battery = integer(root, "batteryPercent", 0, 100, problems);

        DeviceState state = null;
        String rawState = text(root, "status");
        if (rawState == null) {
            problems.add("status is missing");
        } else {
            try {
                state = DeviceState.valueOf(rawState);
            } catch (IllegalArgumentException e) {
                problems.add("status \"" + shorten(rawState) + "\" is not one of " + STATES);
            }
        }

        if (!problems.isEmpty()) {
            return ValidationResult.invalid(shorten(deviceId), shorten(eventId), String.join("; ", problems));
        }
        return ValidationResult.valid(new DeviceEvent(eventId, deviceId, capturedAt, latitude, longitude, battery, state));
    }

    /** Non-blank string value, or null if absent, null, blank or not a string. */
    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }

    private static Double number(JsonNode root, String field, double min, double max, List<String> problems) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            problems.add(field + " is missing");
            return null;
        }
        if (!node.isNumber()) {
            problems.add(field + " must be a number");
            return null;
        }
        double value = node.asDouble();
        if (value < min || value > max) {
            problems.add(field + " " + node.asText() + " is outside " + fmt(min) + ".." + fmt(max));
            return null;
        }
        return value;
    }

    private static Integer integer(JsonNode root, String field, int min, int max, List<String> problems) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            problems.add(field + " is missing");
            return null;
        }
        if (!node.isNumber() || !node.canConvertToExactIntegral() || !node.canConvertToInt()) {
            problems.add(field + " must be a whole number");
            return null;
        }
        int value = node.asInt();
        if (value < min || value > max) {
            problems.add(field + " " + value + " is outside " + min + ".." + max);
            return null;
        }
        return value;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String human(Duration d) {
        return d.toSeconds() % 60 == 0 ? d.toMinutes() + " min" : d.toSeconds() + " s";
    }

    private static String shorten(String s) {
        return s == null || s.length() <= 128 ? s : s.substring(0, 128) + "…";
    }
}
