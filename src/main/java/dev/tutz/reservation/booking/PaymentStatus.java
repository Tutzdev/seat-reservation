package dev.tutz.reservation.booking;

public enum PaymentStatus {
    /** The charge was sent to the ledger and its outcome is not known yet. */
    PENDING,
    /** The ledger refused the charge; nothing moved and the customer can try again. */
    DECLINED,
    /** The money moved and the seats are sold. */
    CAPTURED
}
