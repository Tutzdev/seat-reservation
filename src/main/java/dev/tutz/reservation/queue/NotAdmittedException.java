package dev.tutz.reservation.queue;

import java.time.Duration;

/** Seats of an event with a waiting room can only be held by customers it let in. Answered with 403. */
public class NotAdmittedException extends RuntimeException {

    private final Duration retryAfter;

    NotAdmittedException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /** How long until the customer's turn; {@code null} when they are not in the waiting room at all. */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
