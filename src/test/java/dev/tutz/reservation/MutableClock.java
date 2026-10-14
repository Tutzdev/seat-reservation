package dev.tutz.reservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/** Follows the system clock until a test moves it, so expiry can be tested without sleeping. */
public class MutableClock extends Clock {

    private volatile Instant fixed;

    @Override
    public Instant instant() {
        Instant current = fixed;
        // PostgreSQL keeps microseconds; truncating here keeps values equal after a round trip.
        return current != null ? current : Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public void advance(Duration duration) {
        fixed = instant().plus(duration);
    }

    public void reset() {
        fixed = null;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("The test clock is always UTC.");
    }
}
