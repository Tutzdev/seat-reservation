package dev.tutz.reservation.booking;

import static dev.tutz.reservation.FakePaymentGateway.Answer.TIME_OUT_AFTER_CHARGING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tutz.reservation.Concurrently;
import dev.tutz.reservation.FakePaymentGateway.Charge;
import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.event.EventResponse;
import dev.tutz.reservation.payment.PaymentUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A payment the ledger says it posted is settled from that event, once, whatever the HTTP answer did. */
class LedgerEventTests extends PostgresTestSupport {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.fromString("00000000-0000-0000-0000-00000000c0de");
    private static final Duration PAST_THE_HOLD = Duration.ofMinutes(10).plusSeconds(1);

    @Autowired
    private PaymentService payments;

    @Autowired
    private LedgerEventHandler handler;

    @Autowired
    private LedgerInboxRepository inbox;

    @Test
    void aChargeWhoseAnswerWasLostIsConfirmedByItsEvent() {
        BookingResponse booking = holdOneSeat();
        payWithLostAnswer(booking.id());
        Charge charge = ledger.chargeOf(booking.id()).orElseThrow();

        assertThat(handler.handle(chargeEvent(booking.id(), charge))).isTrue();

        BookingResponse confirmed = bookings.find(booking.id());
        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmed.payment().status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(confirmed.payment().transferId()).isEqualTo(charge.transferId());
        assertThat(ledger.chargeCount()).isEqualTo(1);
    }

    @Test
    void aRedeliveredEventChangesNothing() {
        BookingResponse booking = holdOneSeat();
        payWithLostAnswer(booking.id());
        LedgerTransfer event = chargeEvent(booking.id(), ledger.chargeOf(booking.id()).orElseThrow());

        assertThat(handler.handle(event)).isTrue();
        assertThat(handler.handle(event)).isFalse();

        assertThat(inbox.findByBookingId(booking.id())).hasSize(1);
        assertThat(bookings.find(booking.id()).status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void concurrentDeliveriesOfTheSameEventApplyItOnce() throws Exception {
        BookingResponse booking = holdOneSeat();
        payWithLostAnswer(booking.id());
        LedgerTransfer event = chargeEvent(booking.id(), ledger.chargeOf(booking.id()).orElseThrow());

        List<Boolean> applied = Concurrently.run(10, () -> handler.handle(event));

        assertThat(applied).containsOnlyOnce(true);
        assertThat(inbox.findByBookingId(booking.id())).hasSize(1);
        assertThat(bookings.find(booking.id()).payment().status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void anEventForAPaymentTheHttpAnswerAlreadySettledChangesNothing() {
        BookingResponse booking = holdOneSeat();
        payments.pay(booking.customerId(), booking.id(), new PayRequest(CUSTOMER));
        Charge charge = ledger.chargeOf(booking.id()).orElseThrow();

        handler.handle(chargeEvent(booking.id(), charge));

        BookingResponse confirmed = bookings.find(booking.id());
        assertThat(confirmed.payment().status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(confirmed.payment().transferId()).isEqualTo(charge.transferId());
        assertThat(ledger.refundCount()).isZero();
    }

    @Test
    void aChargeThatLandsAfterTheSeatsWereResoldIsRefunded() {
        EventResponse event = eventWithSeats(1);
        List<Long> seats = seatIdsOf(event);
        BookingResponse late = hold(new HoldRequest(event.id(), seats));
        payWithLostAnswer(late.id());
        clock.advance(PAST_THE_HOLD);
        BookingResponse quicker = hold(new HoldRequest(event.id(), seats));

        handler.handle(chargeEvent(late.id(), ledger.chargeOf(late.id()).orElseThrow()));

        BookingResponse refunded = bookings.find(late.id());
        assertThat(refunded.status()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(refunded.payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.payment().refundTransferId()).isEqualTo(ledger.refundOf(late.id()).orElseThrow());
        assertThat(bookings.find(quicker.id()).status()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void aReversalEventSettlesARefundTheLedgerTookWithoutAnswering() {
        BookingResponse booking = holdOneSeat();
        ledger.beforeCharge(() -> bookings.cancel(booking.customerId(), booking.id()));
        ledger.failNextRefund();
        assertThatThrownBy(() -> payments.pay(booking.customerId(), booking.id(), new PayRequest(CUSTOMER)))
                .isInstanceOf(ConflictException.class);
        UUID chargeId = ledger.chargeOf(booking.id()).orElseThrow().transferId();
        UUID reversalId = UUID.randomUUID();

        handler.handle(new LedgerTransfer(UUID.randomUUID(), "REVERSAL", reversalId, MERCHANT, CUSTOMER, chargeId,
                "refund-" + booking.id()));

        BookingResponse refunded = bookings.find(booking.id());
        assertThat(refunded.payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.payment().refundTransferId()).isEqualTo(reversalId);
    }

    @Test
    void transfersThisServiceDidNotAskForAreIgnored() {
        BookingResponse booking = holdOneSeat();
        payWithLostAnswer(booking.id());
        UUID id = booking.id();
        UUID someoneElse = UUID.randomUUID();

        List<LedgerTransfer> notOurs = List.of(
                new LedgerTransfer(UUID.randomUUID(), "DEPOSIT", UUID.randomUUID(), someoneElse, MERCHANT, null,
                        "booking-" + id),
                new LedgerTransfer(UUID.randomUUID(), "TRANSFER", UUID.randomUUID(), CUSTOMER, someoneElse, null,
                        "booking-" + id),
                new LedgerTransfer(UUID.randomUUID(), "TRANSFER", UUID.randomUUID(), CUSTOMER, MERCHANT, null,
                        "booking-" + UUID.randomUUID()),
                new LedgerTransfer(UUID.randomUUID(), "TRANSFER", UUID.randomUUID(), CUSTOMER, MERCHANT, null,
                        "checkout-" + id),
                new LedgerTransfer(UUID.randomUUID(), "TRANSFER", UUID.randomUUID(), CUSTOMER, MERCHANT, null,
                        "booking-not-a-uuid"),
                new LedgerTransfer(UUID.randomUUID(), "REVERSAL", UUID.randomUUID(), someoneElse, CUSTOMER,
                        UUID.randomUUID(), "refund-" + id));

        assertThat(notOurs).allSatisfy(event -> assertThat(handler.handle(event)).isFalse());
        assertThat(inbox.findByBookingId(id)).isEmpty();
        assertThat(bookings.find(id).payment().status()).isEqualTo(PaymentStatus.PENDING);
    }

    private BookingResponse holdOneSeat() {
        EventResponse event = eventWithSeats(1);
        return hold(new HoldRequest(event.id(), seatIdsOf(event)));
    }

    /** The ledger posts the charge, but the answer never comes back: the payment stays PENDING. */
    private void payWithLostAnswer(UUID bookingId) {
        ledger.answerNext(TIME_OUT_AFTER_CHARGING);
        assertThatThrownBy(() -> payments.pay(bookings.find(bookingId).customerId(), bookingId, new PayRequest(CUSTOMER)))
                .isInstanceOf(PaymentUnavailableException.class);
    }

    private static LedgerTransfer chargeEvent(UUID bookingId, Charge charge) {
        return new LedgerTransfer(UUID.randomUUID(), "TRANSFER", charge.transferId(), charge.customerAccountId(),
                MERCHANT, null, "booking-" + bookingId);
    }
}
