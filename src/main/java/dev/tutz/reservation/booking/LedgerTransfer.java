package dev.tutz.reservation.booking;

import java.util.UUID;

/**
 * A transfer the ledger announced on its event stream, reduced to what this service acts on.
 *
 * @param eventId the ledger's id for the event, the same on every delivery of it
 * @param type the ledger's transfer type: {@code TRANSFER} for a charge, {@code REVERSAL} for a refund
 * @param reversalOfId the transfer a reversal gives back, null otherwise
 * @param idempotencyKey the key the transfer was requested with, {@code booking-{id}} or {@code refund-{id}}
 *        when this service asked for it
 */
public record LedgerTransfer(
        UUID eventId,
        String type,
        UUID transferId,
        UUID sourceAccountId,
        UUID destinationAccountId,
        UUID reversalOfId,
        String idempotencyKey) {

    static final String CHARGE = "TRANSFER";
    static final String REVERSAL = "REVERSAL";
}
