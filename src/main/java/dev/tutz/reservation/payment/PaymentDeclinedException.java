package dev.tutz.reservation.payment;

/**
 * The ledger answered and said no (insufficient funds, frozen account...). Nothing was charged,
 * so the customer can fix the cause and try again.
 */
public class PaymentDeclinedException extends RuntimeException {

    private final String reason;

    public PaymentDeclinedException(String reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** The ledger's own error code, passed on so clients can tell "no funds" from "account frozen". */
    public String getReason() {
        return reason;
    }
}
