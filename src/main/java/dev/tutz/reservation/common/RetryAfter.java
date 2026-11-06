package dev.tutz.reservation.common;

import java.time.Duration;

/** Retry-After is a whole number of seconds; rounding down would send clients back a moment too early. */
public final class RetryAfter {

    private RetryAfter() {
    }

    public static long seconds(Duration wait) {
        long seconds = wait.toSeconds();
        return Math.max(1, wait.equals(Duration.ofSeconds(seconds)) ? seconds : seconds + 1);
    }
}
