package dev.tutz.reservation.booking;

public enum PaymentStatus {
    /** The charge was sent to the ledger and its outcome is not known yet. */
    PENDING,
    /** The ledger refused the charge; nothing moved and the customer can try again. */
    DECLINED,
    /** The money moved and the seats are sold. */
    CAPTURED,
    /** The money moved but the booking ended first, so it has to go back. */
    REFUND_PENDING,
    /** The money went back to the customer. */
    REFUNDED
}
