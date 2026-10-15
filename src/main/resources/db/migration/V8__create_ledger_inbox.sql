-- Ledger events this service has acted on. The ledger publishes at least once, so the same event can be
-- delivered again. Its id is inserted in the same transaction that applies it, so a redelivery finds the
-- row and changes nothing, and two deliveries racing each other meet on the primary key.
CREATE TABLE ledger_inbox (
    event_id    UUID PRIMARY KEY,
    booking_id  UUID        NOT NULL REFERENCES bookings (id),
    transfer_id UUID        NOT NULL,
    received_at TIMESTAMPTZ NOT NULL
);
