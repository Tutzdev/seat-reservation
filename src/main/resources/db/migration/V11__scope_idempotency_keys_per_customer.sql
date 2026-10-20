-- Clients choose their own Idempotency-Keys, so two customers can pick the same one by chance. Unique per
-- customer, the second customer's key is simply another request, instead of a conflict with a stranger's booking.
ALTER TABLE bookings
    DROP CONSTRAINT uk_bookings_idempotency_key,
    ADD CONSTRAINT uk_bookings_customer_idempotency_key UNIQUE (customer_id, idempotency_key);
