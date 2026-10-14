CREATE TABLE bookings (
    id          UUID PRIMARY KEY,
    event_id    UUID        NOT NULL REFERENCES events (id),
    status      VARCHAR(16) NOT NULL CHECK (status IN ('HELD', 'CANCELLED', 'EXPIRED')),
    total_cents BIGINT      NOT NULL CHECK (total_cents >= 0),
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);

-- What the booking asked for and at which price. Unlike seats.booking_id, these lines never change,
-- so a cancelled or expired booking still shows which seats it had.
CREATE TABLE booking_seats (
    booking_id  UUID        NOT NULL REFERENCES bookings (id),
    seat_id     BIGINT      NOT NULL REFERENCES seats (id),
    section     VARCHAR(40) NOT NULL,
    row_label   VARCHAR(8)  NOT NULL,
    number      INT         NOT NULL,
    price_cents BIGINT      NOT NULL,
    PRIMARY KEY (booking_id, seat_id)
);

-- The seat row is the single source of truth for who holds it right now.
ALTER TABLE seats
    ADD COLUMN status     VARCHAR(16) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'HELD')),
    ADD COLUMN booking_id UUID REFERENCES bookings (id),
    ADD COLUMN held_until TIMESTAMPTZ,
    ADD CONSTRAINT ck_seats_hold_consistency CHECK (
        (status = 'AVAILABLE' AND booking_id IS NULL AND held_until IS NULL)
        OR (status = 'HELD' AND booking_id IS NOT NULL AND held_until IS NOT NULL));

CREATE INDEX ix_seats_booking ON seats (booking_id) WHERE booking_id IS NOT NULL;
