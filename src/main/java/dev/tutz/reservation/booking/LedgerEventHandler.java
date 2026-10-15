package dev.tutz.reservation.booking;

import dev.tutz.reservation.payment.LedgerProperties;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Settles payments from the ledger's own record of what happened. When the ledger posts the charge of a
 * booking, the payment is captured here even if the HTTP answer was lost and nobody retries; when it posts
 * a refund, the payment is marked refunded. Without this, both would wait for the reconciler.
 *
 * <p>The ledger publishes at least once, so an event may arrive twice. Its id is claimed in the inbox in the
 * same transaction as the change it causes, so a redelivery changes nothing. An event can also race the HTTP
 * answer to the same charge; both lock the booking, and the second finds the payment already settled.
 */
@Component
public class LedgerEventHandler {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventHandler.class);
    private static final String CHARGE_KEY_PREFIX = "booking-";
    private static final String REFUND_KEY_PREFIX = "refund-";

    private final LedgerInboxRepository inbox;
    private final PaymentRepository payments;
    private final PaymentRecorder recorder;
    private final PaymentService service;
    private final TransactionTemplate transaction;
    private final LedgerProperties ledger;
    private final Clock clock;

    LedgerEventHandler(LedgerInboxRepository inbox, PaymentRepository payments, PaymentRecorder recorder,
            PaymentService service, TransactionTemplate transaction, LedgerProperties ledger, Clock clock) {
        this.inbox = inbox;
        this.payments = payments;
        this.recorder = recorder;
        this.service = service;
        this.transaction = transaction;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * @return true the first time an event about one of this service's payments arrives (the payment may
     *         have been settled already by the HTTP answer); false for a redelivery or an event that is not ours
     */
    public boolean handle(LedgerTransfer transfer) {
        Optional<UUID> bookingId = bookingOf(transfer).filter(payments::existsById);
        if (bookingId.isEmpty()) {
            return false;
        }
        Applied applied = transaction.execute(status -> apply(transfer, bookingId.get()));
        // The refund is an HTTP call, so it runs after the commit. If it fails, the payment stays
        // REFUND_PENDING and the reconciler pushes it again.
        applied.toRefund().ifPresent(service::settle);
        return applied.firstTime();
    }

    private Applied apply(LedgerTransfer transfer, UUID bookingId) {
        if (inbox.record(transfer.eventId(), bookingId, transfer.transferId(), clock.instant()) == 0) {
            log.debug("Ledger event {} was already applied", transfer.eventId());
            return new Applied(false, Optional.empty());
        }
        if (LedgerTransfer.CHARGE.equals(transfer.type())) {
            return new Applied(true, recorder.capture(bookingId, transfer.transferId()));
        }
        Payment payment = payments.findById(bookingId).orElseThrow();
        if (transfer.reversalOfId().equals(payment.getTransferId())) {
            recorder.refunded(bookingId, transfer.transferId());
        }
        return new Applied(true, Optional.empty());
    }

    /**
     * Only transfers this service asked for: a charge into the venue's account with a booking key, or a
     * reversal out of it with a refund key. Deposits, other customers' transfers and anything else on the
     * ledger are not ours.
     */
    private Optional<UUID> bookingOf(LedgerTransfer transfer) {
        UUID merchant = ledger.merchantAccountId();
        if (LedgerTransfer.CHARGE.equals(transfer.type()) && merchant.equals(transfer.destinationAccountId())) {
            return idAfter(CHARGE_KEY_PREFIX, transfer.idempotencyKey());
        }
        if (LedgerTransfer.REVERSAL.equals(transfer.type()) && merchant.equals(transfer.sourceAccountId())
                && transfer.reversalOfId() != null) {
            return idAfter(REFUND_KEY_PREFIX, transfer.idempotencyKey());
        }
        return Optional.empty();
    }

    private static Optional<UUID> idAfter(String prefix, String key) {
        if (key == null || !key.startsWith(prefix)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(key.substring(prefix.length())));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    private record Applied(boolean firstTime, Optional<Payment> toRefund) {
    }
}
