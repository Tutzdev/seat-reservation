package dev.tutz.reservation.payment;

/**
 * No usable answer from the ledger: timeout, connection refused, 5xx or rejected service credentials.
 * After a timeout the charge may still have gone through, so the caller must treat the outcome as unknown
 * and repeat the call with the same Idempotency-Key to find out.
 */
public class PaymentUnavailableException extends RuntimeException {

    public PaymentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
