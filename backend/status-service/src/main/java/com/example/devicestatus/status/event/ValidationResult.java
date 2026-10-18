package com.example.devicestatus.status.event;

/**
 * Either a valid {@link DeviceEvent}, or the reason it was rejected plus whatever IDs could be read
 * from the payload (so the rejected row can still show which device sent it).
 */
public record ValidationResult(DeviceEvent event, String reason, String deviceId, String eventId) {

    public static ValidationResult valid(DeviceEvent event) {
        return new ValidationResult(event, null, event.deviceId(), event.eventId());
    }

    public static ValidationResult invalid(String deviceId, String eventId, String reason) {
        return new ValidationResult(null, reason, deviceId, eventId);
    }

    public boolean isValid() {
        return event != null;
    }
}
