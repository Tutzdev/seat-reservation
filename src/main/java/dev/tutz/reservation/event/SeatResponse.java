package dev.tutz.reservation.event;

import java.time.Instant;

public record SeatResponse(long id, String section, String row, int number, long priceCents, SeatStatus status) {

    static SeatResponse from(Seat seat, Instant now) {
        SeatStatus status = seat.isAvailableAt(now) ? SeatStatus.AVAILABLE : seat.getStatus();
        return new SeatResponse(seat.getId(), seat.getSection(), seat.getRowLabel(), seat.getNumber(),
                seat.getPriceCents(), status);
    }
}
