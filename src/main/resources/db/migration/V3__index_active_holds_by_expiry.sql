-- The expiry sweeper only ever looks for HELD bookings past their deadline. A partial index keeps
-- that lookup small no matter how many cancelled or expired bookings pile up over time.
CREATE INDEX ix_bookings_held_expires_at ON bookings (expires_at) WHERE status = 'HELD';
