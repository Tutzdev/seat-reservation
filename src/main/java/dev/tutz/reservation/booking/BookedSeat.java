package dev.tutz.reservation.booking;

import dev.tutz.reservation.event.Seat;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** A line of the booking: the seat and the price it had when it was held. */
@Embeddable
public record BookedSeat(
        @Column(nullable = false) long seatId,
        @Column(nullable = false, length = 40) String section,
        @Column(nullable = false, length = 8) String rowLabel,
        @Column(nullable = false) int number,
        @Column(nullable = false) long priceCents) {

    static BookedSeat of(Seat seat) {
        return new BookedSeat(seat.getId(), seat.getSection(), seat.getRowLabel(), seat.getNumber(), seat.getPriceCents());
    }
}
