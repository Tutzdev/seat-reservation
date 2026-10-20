-- A customer holds seats for an event through one booking at a time, so one person cannot spread holds over
-- many bookings and block a whole section. The index enforces it even for requests that arrive together.
CREATE UNIQUE INDEX ux_bookings_one_hold_per_customer ON bookings (customer_id, event_id) WHERE status = 'HELD';
