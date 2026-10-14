package dev.tutz.reservation.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        UUID eventId,
        BookingStatus status,
        long totalCents,
        Instant expiresAt,
        Instant createdAt,
        List<SeatLine> seats) {

    public BookingResponse {
        seats = List.copyOf(seats);
    }

    static BookingResponse from(Booking booking, Instant now) {
        List<SeatLine> seats = booking.getSeats().stream()
                .map(seat -> new SeatLine(seat.seatId(), seat.section(), seat.rowLabel(), seat.number(), seat.priceCents()))
                .toList();
        return new BookingResponse(booking.getId(), booking.getEventId(), booking.statusAt(now), booking.getTotalCents(),
                booking.getExpiresAt(), booking.getCreatedAt(), seats);
    }

    public record SeatLine(long seatId, String section, String row, int number, long priceCents) {
    }
}
