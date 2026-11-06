package dev.tutz.reservation.queue;

import java.time.Instant;
import java.util.UUID;

/**
 * @param admitted whether the customer may hold seats now
 * @param position about how many buyers will be let in before this one; 0 once admitted
 * @param admittedAt when the customer is let in; poll until then
 * @param passExpiresAt until when an admitted customer may start a hold
 */
public record QueueResponse(UUID eventId, boolean admitted, long position, Instant admittedAt,
        Instant passExpiresAt) {
}
