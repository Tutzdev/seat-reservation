package dev.tutz.reservation.booking;

public enum BookingStatus {
    HELD,
    CONFIRMED,
    CANCELLED,
    EXPIRED,
    /** A confirmed booking the customer gave back; its seats are on sale again. */
    REFUNDED
}
