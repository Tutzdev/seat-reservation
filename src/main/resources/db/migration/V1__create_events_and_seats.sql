CREATE TABLE events (
    id         UUID PRIMARY KEY,
    name       VARCHAR(120) NOT NULL,
    venue      VARCHAR(120) NOT NULL,
    starts_at  TIMESTAMPTZ  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_events_starts_at ON events (starts_at);

-- Seats are inserted in batches when an event is created; the sequence hands out
-- blocks of 100 ids so Hibernate does not need a round trip per seat.
CREATE SEQUENCE seats_id_seq INCREMENT BY 100;

CREATE TABLE seats (
    id          BIGINT PRIMARY KEY,
    event_id    UUID        NOT NULL REFERENCES events (id),
    section     VARCHAR(40) NOT NULL,
    row_label   VARCHAR(8)  NOT NULL,
    number      INT         NOT NULL CHECK (number > 0),
    price_cents BIGINT      NOT NULL CHECK (price_cents >= 0),
    CONSTRAINT uk_seats_position UNIQUE (event_id, section, row_label, number)
);
