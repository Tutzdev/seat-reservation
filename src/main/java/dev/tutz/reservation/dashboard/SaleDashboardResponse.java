package dev.tutz.reservation.dashboard;

import dev.tutz.reservation.event.SeatCounts;
import java.time.Instant;
import java.util.UUID;

/**
 * A sale as the organizer watches it.
 *
 * @param holds holds won and lost to a faster buyer since the sale opened
 * @param waitingRoom {@code null} when the sale has no waiting room
 */
public record SaleDashboardResponse(UUID eventId, Instant salesOpenAt, SeatCounts seats, Holds holds,
        WaitingLine waitingRoom) {

    public record Holds(long won, long lost) {
    }

    /**
     * @param waiting about how many buyers have joined and are not let in yet
     * @param clearsAt when the last of them is let in; {@code null} when nobody is waiting
     */
    public record WaitingLine(int admitsPerMinute, long waiting, Instant clearsAt) {
    }
}
