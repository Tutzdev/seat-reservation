package dev.tutz.reservation.booking;

import dev.tutz.reservation.payment.PaymentDeclinedException;
import dev.tutz.reservation.payment.PaymentUnavailableException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Settles payments nobody is waiting for anymore: a charge whose answer was lost and whose customer closed
 * the app, or a refund the ledger did not take. It repeats the same idempotent ledger calls a client retry
 * would make, so running it next to a live request, or on several instances at once, is safe.
 */
@Component
public class PaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

    private final PaymentRepository payments;
    private final PaymentService service;
    private final PaymentProperties properties;
    private final Clock clock;

    public PaymentReconciler(PaymentRepository payments, PaymentService service, PaymentProperties properties,
            Clock clock) {
        this.payments = payments;
        this.service = service;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${reservation.payments.reconcile.interval}",
            initialDelayString = "${reservation.payments.reconcile.interval}")
    public void reconcile() {
        Instant untouchedSince = clock.instant().minus(properties.reconcile().after());
        List<UUID> unsettled = payments.findUnsettled(untouchedSince, properties.reconcile().batchSize());
        for (UUID bookingId : unsettled) {
            Payment payment = payments.findById(bookingId).orElseThrow();
            try {
                service.settle(payment);
            } catch (PaymentDeclinedException declined) {
                log.info("Pending payment of booking {} was declined: {}", bookingId, declined.getReason());
            } catch (PaymentUnavailableException unavailable) {
                // Every other payment would fail the same way; try again on the next run.
                log.warn("Ledger unavailable, stopping reconciliation: {}", unavailable.getMessage());
                return;
            }
        }
    }
}
