package dev.tutz.reservation.queue;

import java.time.Duration;
import java.time.Instant;

/**
 * A customer's place in an event's waiting room.
 *
 * @param admittedAt when the customer is let in: in the future while they wait
 * @param passExpiresAt until when they may start holding seats; after that they have to queue again
 */
public record QueueTicket(Instant admittedAt, Instant passExpiresAt) {

    public boolean isAdmittedAt(Instant now) {
        return !now.isBefore(admittedAt) && now.isBefore(passExpiresAt);
    }

    boolean isValidAt(Instant now) {
        return now.isBefore(passExpiresAt);
    }

    /** How long is left to wait, zero once admitted. */
    public Duration waitAt(Instant now) {
        return now.isBefore(admittedAt) ? Duration.between(now, admittedAt) : Duration.ZERO;
    }
}
