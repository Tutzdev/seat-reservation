package dev.tutz.reservation.booking;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param reconcile how often unsettled payments are taken further, how old they must be first (so a
 *        request still waiting on the ledger is left alone), and how many are handled per run
 */
@ConfigurationProperties("reservation.payments")
public record PaymentProperties(Reconcile reconcile) {

    public record Reconcile(Duration interval, Duration after, int batchSize) {
    }
}
