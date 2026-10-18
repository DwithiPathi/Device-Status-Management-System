package com.example.devicestatus.status.event;

import java.time.Instant;

/**
 * One validated location reading from a device.
 *
 * @param capturedAt when the device took the reading (not when the server received it); this is
 *                   what decides which reading is the latest.
 */
public record DeviceEvent(String eventId,
                          String deviceId,
                          Instant capturedAt,
                          double latitude,
                          double longitude,
                          int batteryPercent,
                          DeviceState status) {
}
