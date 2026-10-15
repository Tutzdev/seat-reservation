-- A paid booking keeps its seats for good: a SOLD seat has an owner and no deadline.
ALTER TABLE seats
    DROP CONSTRAINT seats_status_check,
    DROP CONSTRAINT ck_seats_hold_consistency,
    ADD CONSTRAINT ck_seats_status CHECK (status IN ('AVAILABLE', 'HELD', 'SOLD')),
    ADD CONSTRAINT ck_seats_hold_consistency CHECK (
        (status = 'AVAILABLE' AND booking_id IS NULL AND held_until IS NULL)
        OR (status = 'HELD' AND booking_id IS NOT NULL AND held_until IS NOT NULL)
        OR (status = 'SOLD' AND booking_id IS NOT NULL AND held_until IS NULL));

ALTER TABLE bookings
    DROP CONSTRAINT bookings_status_check,
    ADD CONSTRAINT ck_bookings_status CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED'));

-- One payment per booking. The ledger transfer id is only known once the HTTP call returns, so a payment
-- is written as PENDING before the call: if the answer is lost, the row still says a charge may exist.
CREATE TABLE payments (
    booking_id          UUID PRIMARY KEY REFERENCES bookings (id),
    customer_account_id UUID        NOT NULL,
    amount_cents        BIGINT      NOT NULL CHECK (amount_cents > 0),
    status              VARCHAR(16) NOT NULL CHECK (status IN ('PENDING', 'DECLINED', 'CAPTURED')),
    transfer_id         UUID,
    decline_reason      VARCHAR(40),
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_payments_outcome CHECK (
        (status = 'PENDING' AND transfer_id IS NULL AND decline_reason IS NULL)
        OR (status = 'DECLINED' AND transfer_id IS NULL AND decline_reason IS NOT NULL)
        OR (status = 'CAPTURED' AND transfer_id IS NOT NULL AND decline_reason IS NULL))
);
