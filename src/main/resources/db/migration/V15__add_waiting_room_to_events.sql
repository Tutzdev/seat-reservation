-- A big sale opens behind a virtual waiting room that lets buyers in at a steady pace. NULL means no waiting
-- room: anyone can hold seats as soon as the sale opens.
ALTER TABLE events
    ADD COLUMN admits_per_minute INT CONSTRAINT ck_events_admits_per_minute CHECK (admits_per_minute BETWEEN 1 AND 60000);
