-- A retried hold request must return the booking it already created, not a second one.
-- The fingerprint tells a genuine retry from a different request that reuses the same key.
ALTER TABLE bookings
    ADD COLUMN idempotency_key     VARCHAR(64) NOT NULL,
    ADD COLUMN request_fingerprint VARCHAR(64) NOT NULL,
    ADD CONSTRAINT uk_bookings_idempotency_key UNIQUE (idempotency_key);
