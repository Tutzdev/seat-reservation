-- Seats go on sale at a set time, often weeks after the event is announced. Events created before this
-- migration were on sale from the moment they were created.
ALTER TABLE events ADD COLUMN sales_open_at TIMESTAMPTZ;

UPDATE events SET sales_open_at = created_at;

ALTER TABLE events
    ALTER COLUMN sales_open_at SET NOT NULL,
    ADD CONSTRAINT ck_events_sales_open_before_start CHECK (sales_open_at < starts_at);
