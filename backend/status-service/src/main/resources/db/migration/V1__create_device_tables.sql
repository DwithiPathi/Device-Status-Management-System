-- Every unique event a device ever sent. (device_id, event_id) is the dedup key:
-- a re-delivered or re-sent event hits the UNIQUE constraint and is ignored.
CREATE TABLE device_event_history (
    id              BIGSERIAL        PRIMARY KEY,
    device_id       VARCHAR(128)     NOT NULL,
    event_id        VARCHAR(128)     COLLATE "C" NOT NULL,
    captured_at     TIMESTAMPTZ      NOT NULL,
    latitude        DOUBLE PRECISION NOT NULL,
    longitude       DOUBLE PRECISION NOT NULL,
    battery_percent INTEGER          NOT NULL,
    status          VARCHAR(32)      NOT NULL,
    kafka_partition INTEGER,
    kafka_offset    BIGINT,
    received_at     TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT uq_device_event UNIQUE (device_id, event_id)
);

CREATE INDEX ix_history_device_captured ON device_event_history (device_id, captured_at DESC, event_id DESC);

-- Exactly one row per device: its newest reading (by captured_at, then event_id).
CREATE TABLE device_latest_status (
    device_id       VARCHAR(128)     PRIMARY KEY,
    event_id        VARCHAR(128)     COLLATE "C" NOT NULL,
    captured_at     TIMESTAMPTZ      NOT NULL,
    latitude        DOUBLE PRECISION NOT NULL,
    longitude       DOUBLE PRECISION NOT NULL,
    battery_percent INTEGER          NOT NULL,
    status          VARCHAR(32)      NOT NULL,
    updated_at      TIMESTAMPTZ      NOT NULL DEFAULT now()
);

-- Messages that failed validation, with the reason and the raw payload, for troubleshooting.
CREATE TABLE device_event_rejected (
    id              BIGSERIAL    PRIMARY KEY,
    device_id       VARCHAR(256),
    event_id        VARCHAR(256),
    reason          TEXT         NOT NULL,
    payload         TEXT,
    kafka_partition INTEGER,
    kafka_offset    BIGINT,
    rejected_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
