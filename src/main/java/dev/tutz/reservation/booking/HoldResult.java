package dev.tutz.reservation.booking;

/** @param replayed true when the Idempotency-Key matched a booking created by an earlier request */
public record HoldResult(BookingResponse booking, boolean replayed) {

    static HoldResult created(BookingResponse booking) {
        return new HoldResult(booking, false);
    }

    static HoldResult replayed(BookingResponse booking) {
        return new HoldResult(booking, true);
    }
}
