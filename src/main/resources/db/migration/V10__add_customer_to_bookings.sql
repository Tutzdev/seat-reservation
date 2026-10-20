-- A booking belongs to the customer who held it. Bookings from before accounts existed keep a null owner:
-- nobody can read or change them through the API anymore, and every new booking has one.
ALTER TABLE bookings ADD COLUMN customer_id UUID REFERENCES customers (id);

CREATE INDEX ix_bookings_customer ON bookings (customer_id, created_at);
