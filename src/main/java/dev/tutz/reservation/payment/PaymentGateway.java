package dev.tutz.reservation.payment;

import java.util.UUID;

/**
 * Where the money moves. Both calls are idempotent by key: repeating a call whose answer was lost
 * returns the transfer made the first time instead of moving the money again.
 */
public interface PaymentGateway {

    /**
     * Charges the customer's account and credits the venue's account.
     *
     * @return the id of the transfer that moved the money
     * @throws PaymentDeclinedException if the ledger refused the charge; no money moved
     * @throws PaymentUnavailableException if the outcome is unknown; the money may or may not have moved
     */
    UUID charge(String idempotencyKey, UUID customerAccountId, long amountCents, String description);

    /**
     * Gives a charge back in full.
     *
     * @return the id of the reversal transfer
     */
    UUID refund(UUID transferId, String idempotencyKey, String reason);
}
