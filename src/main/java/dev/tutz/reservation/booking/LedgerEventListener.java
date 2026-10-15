package dev.tutz.reservation.booking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Feeds wallet-ledger's transfer events from Kafka into {@link LedgerEventHandler}.
 *
 * <p>Off unless {@code reservation.payments.events.enabled} is true. The events only make settling faster:
 * without them the reconciler still settles every payment, a minute later. That is also why an event that
 * keeps failing is logged and skipped instead of blocking its partition.
 */
@Configuration
@ConditionalOnProperty(name = "reservation.payments.events.enabled", havingValue = "true")
class LedgerEventListener {

    /** Deposits and withdrawals never concern a booking, so they are skipped before parsing. */
    private static final Set<String> EVENT_TYPES = Set.of("transfer.posted", "reversal.posted");
    private static final long RETRY_INTERVAL_MS = 1_000;
    private static final long RETRIES = 4;

    private final LedgerEventHandler handler;
    private final ObjectMapper json;

    LedgerEventListener(LedgerEventHandler handler, ObjectMapper json) {
        this.handler = handler;
        this.json = json;
    }

    @KafkaListener(topics = "${reservation.payments.events.topic}")
    void onEvent(ConsumerRecord<String, String> record) {
        if (!EVENT_TYPES.contains(header(record, "event-type"))) {
            return;
        }
        UUID eventId = parseId(header(record, "event-id"));
        TransferPosted event;
        try {
            event = json.readValue(record.value(), TransferPosted.class);
        } catch (JacksonException unreadable) {
            throw new MalformedEventException("Event %s is not a transfer: %s".formatted(eventId, unreadable.getMessage()));
        }
        handler.handle(new LedgerTransfer(eventId, event.type(), event.transferId(), event.sourceAccountId(),
                event.destinationAccountId(), event.reversalOfId(), event.idempotencyKey()));
    }

    /**
     * A failure such as the database being down is retried a few times, a second apart. A malformed event
     * would fail the same way forever, so it is skipped at once. Spring Boot applies this to every listener.
     */
    @Bean
    DefaultErrorHandler ledgerEventErrorHandler() {
        DefaultErrorHandler errors = new DefaultErrorHandler(new FixedBackOff(RETRY_INTERVAL_MS, RETRIES));
        errors.addNotRetryableExceptions(MalformedEventException.class);
        return errors;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException missing) {
            throw new MalformedEventException("Event has no valid event-id header: " + value);
        }
    }

    /** The ledger's TransferPostedEvent; only the fields this service reads. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record TransferPosted(UUID transferId, String type, UUID sourceAccountId, UUID destinationAccountId,
            UUID reversalOfId, String idempotencyKey) {
    }

    static class MalformedEventException extends RuntimeException {

        MalformedEventException(String message) {
            super(message);
        }
    }
}
