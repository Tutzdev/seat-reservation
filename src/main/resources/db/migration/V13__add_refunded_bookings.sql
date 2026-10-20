-- A confirmed booking can be given back by its customer before the event: its seats go back on sale and its
-- payment is refunded.
ALTER TABLE bookings
    DROP CONSTRAINT ck_bookings_status,
    ADD CONSTRAINT ck_bookings_status CHECK (status IN ('HELD', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'REFUNDED'));
