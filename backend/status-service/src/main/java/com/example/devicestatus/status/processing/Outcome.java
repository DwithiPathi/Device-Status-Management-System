package com.example.devicestatus.status.processing;

/** What happened to one Kafka message. */
public enum Outcome {
    /** New event, and it is the device's newest reading: latest status moved forward. */
    LATEST_UPDATED,
    /** New event, but older than the stored latest (arrived late): kept in history only. */
    STORED_OLDER_EVENT,
    /** Same (deviceId, eventId) was already stored: nothing changed. */
    DUPLICATE_IGNORED,
    /** Failed validation: stored in device_event_rejected with the reason. */
    REJECTED
}
