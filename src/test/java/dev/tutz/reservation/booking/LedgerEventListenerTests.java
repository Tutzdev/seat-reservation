package dev.tutz.reservation.booking;

import static dev.tutz.reservation.FakePaymentGateway.Answer.TIME_OUT_AFTER_CHARGING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import dev.tutz.reservation.FakePaymentGateway.Charge;
import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.event.EventResponse;
import dev.tutz.reservation.payment.PaymentUnavailableException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

/**
 * The whole path from a Kafka record shaped like the ones wallet-ledger publishes to a confirmed booking,
 * on an in-process broker.
 */
@EmbeddedKafka(topics = LedgerEventListenerTests.TOPIC, partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@TestPropertySource(properties = {
        "reservation.payments.events.enabled=true",
        "reservation.payments.events.topic=" + LedgerEventListenerTests.TOPIC})
class LedgerEventListenerTests extends PostgresTestSupport {

    static final String TOPIC = "ledger.transfers";
    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final String MERCHANT = "00000000-0000-0000-0000-00000000c0de";
    private static final Duration DELIVERY = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private PaymentService payments;

    @Autowired
    private LedgerInboxRepository inbox;

    @Test
    void aPostedChargeConfirmsTheBookingWhoseAnswerWasLost() throws Exception {
        UUID bookingId = holdAndLoseTheAnswer();
        Charge charge = ledger.chargeOf(bookingId).orElseThrow();

        publish(UUID.randomUUID().toString(), "transfer.posted", chargePayload(bookingId, charge));

        await().atMost(DELIVERY).untilAsserted(() ->
                assertThat(bookings.find(bookingId).status()).isEqualTo(BookingStatus.CONFIRMED));
        assertThat(bookings.find(bookingId).payment().transferId()).isEqualTo(charge.transferId());
    }

    @Test
    void anEventDeliveredTwiceIsAppliedOnce() throws Exception {
        UUID bookingId = holdAndLoseTheAnswer();
        String eventId = UUID.randomUUID().toString();
        String payload = chargePayload(bookingId, ledger.chargeOf(bookingId).orElseThrow());

        publish(eventId, "transfer.posted", payload);
        publish(eventId, "transfer.posted", payload);
        // A later record on the same partition: once it is applied, both copies above were consumed.
        UUID marker = holdAndLoseTheAnswer();
        publish(UUID.randomUUID().toString(), "transfer.posted", chargePayload(marker, ledger.chargeOf(marker).orElseThrow()));

        await().atMost(DELIVERY).untilAsserted(() ->
                assertThat(bookings.find(marker).status()).isEqualTo(BookingStatus.CONFIRMED));
        assertThat(inbox.findByBookingId(bookingId)).hasSize(1);
        assertThat(bookings.find(bookingId).status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void aMalformedEventIsSkippedAndTheNextOneIsStillApplied() throws Exception {
        UUID bookingId = holdAndLoseTheAnswer();

        publish(UUID.randomUUID().toString(), "transfer.posted", "{not json");
        publish("not-a-uuid", "transfer.posted", chargePayload(bookingId, ledger.chargeOf(bookingId).orElseThrow()));
        publish(UUID.randomUUID().toString(), "transfer.posted",
                chargePayload(bookingId, ledger.chargeOf(bookingId).orElseThrow()));

        await().atMost(DELIVERY).untilAsserted(() ->
                assertThat(bookings.find(bookingId).status()).isEqualTo(BookingStatus.CONFIRMED));
    }

    private UUID holdAndLoseTheAnswer() {
        EventResponse event = eventWithSeats(1);
        UUID bookingId = hold(new HoldRequest(event.id(), seatIdsOf(event))).id();
        ledger.answerNext(TIME_OUT_AFTER_CHARGING);
        assertThatThrownBy(() -> payments.pay(bookingId, new PayRequest(CUSTOMER)))
                .isInstanceOf(PaymentUnavailableException.class);
        return bookingId;
    }

    /** The same JSON wallet-ledger writes for a TransferPostedEvent. */
    private static String chargePayload(UUID bookingId, Charge charge) {
        return """
                {"transferId":"%s","type":"TRANSFER","sourceAccountId":"%s","destinationAccountId":"%s",\
                "amount":100.00,"currency":"BRL","description":"Booking %s","reversalOfId":null,\
                "idempotencyKey":"booking-%s","postedAt":"2026-10-15T12:00:00Z"}"""
                .formatted(charge.transferId(), charge.customerAccountId(), MERCHANT, bookingId, bookingId);
    }

    private void publish(String eventId, String eventType, String payload) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, UUID.randomUUID().toString(), payload);
        record.headers()
                .add("event-id", eventId.getBytes(StandardCharsets.UTF_8))
                .add("event-type", eventType.getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get();
    }
}
