package dev.tutz.reservation.booking;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** @param payment null until someone tries to pay for the booking */
public record BookingResponse(
        UUID id,
        UUID eventId,
        UUID customerId,
        BookingStatus status,
        long totalCents,
        Instant expiresAt,
        Instant createdAt,
        List<SeatLine> seats,
        PaymentLine payment) {

    public BookingResponse {
        seats = List.copyOf(seats);
    }

    static BookingResponse from(Booking booking, Instant now) {
        return from(booking, null, now);
    }

    static BookingResponse from(Booking booking, Payment payment, Instant now) {
        List<SeatLine> seats = booking.getSeats().stream()
                .map(seat -> new SeatLine(seat.seatId(), seat.section(), seat.rowLabel(), seat.number(), seat.priceCents()))
                .toList();
        PaymentLine paymentLine = payment == null ? null : new PaymentLine(payment.getStatus(),
                payment.getCustomerAccountId(), payment.getTransferId(), payment.getRefundTransferId(),
                payment.getDeclineReason());
        return new BookingResponse(booking.getId(), booking.getEventId(), booking.getCustomerId(), booking.statusAt(now),
                booking.getTotalCents(), booking.getExpiresAt(), booking.getCreatedAt(), seats, paymentLine);
    }

    public record SeatLine(long seatId, String section, String row, int number, long priceCents) {
    }

    /**
     * @param transferId the wallet-ledger transfer that moved the money, once it is known
     * @param refundTransferId the reversal that gave it back, if the booking ended before the payment landed
     */
    public record PaymentLine(PaymentStatus status, UUID customerAccountId, UUID transferId, UUID refundTransferId,
            String declineReason) {
    }
}
