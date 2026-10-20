package dev.tutz.reservation.customer;

public enum Role {
    /** Holds, pays for and refunds their own bookings. */
    CUSTOMER,
    /** A customer who may also create events. */
    ORGANIZER
}
