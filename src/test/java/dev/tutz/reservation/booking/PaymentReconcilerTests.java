package dev.tutz.reservation.booking;

import static dev.tutz.reservation.FakePaymentGateway.Answer.TIME_OUT_AFTER_CHARGING;
import static dev.tutz.reservation.FakePaymentGateway.Answer.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.event.EventResponse;
import dev.tutz.reservation.payment.PaymentUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Payments the customer walked away from are still settled: charged and confirmed, or refunded. */
class PaymentReconcilerTests extends PostgresTestSupport {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final Duration PAST_THE_HOLD = Duration.ofMinutes(10).plusSeconds(1);
    private static final Duration PAST_THE_GRACE_PERIOD = Duration.ofMinutes(1).plusSeconds(1);

    @Autowired
    private PaymentService payments;

    @Autowired
    private PaymentReconciler reconciler;

    @Autowired
    private HoldExpirySweeper sweeper;

    @Test
    void aChargeWhoseAnswerWasLostIsConfirmedWithoutChargingAgain() {
        BookingResponse booking = holdOneSeat();
        ledger.answerNext(TIME_OUT_AFTER_CHARGING);
        assertThatThrownBy(() -> payments.pay(booking.id(), new PayRequest(CUSTOMER)))
                .isInstanceOf(PaymentUnavailableException.class);
        UUID transferId = ledger.chargeOf(booking.id()).orElseThrow().transferId();

        clock.advance(PAST_THE_GRACE_PERIOD);
        reconciler.reconcile();

        BookingResponse confirmed = bookings.find(booking.id());
        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmed.payment().transferId()).isEqualTo(transferId);
    }

    @Test
    void aRecentPendingPaymentIsLeftToTheRequestStillWaitingForIt() {
        BookingResponse booking = holdOneSeat();
        ledger.answerNext(UNAVAILABLE);
        assertThatThrownBy(() -> payments.pay(booking.id(), new PayRequest(CUSTOMER)))
                .isInstanceOf(PaymentUnavailableException.class);

        reconciler.reconcile();

        assertThat(bookings.find(booking.id()).payment().status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(ledger.chargeOf(booking.id())).isEmpty();
    }

    @Test
    void aLostChargeForSeatsThatWereResoldIsRefunded() {
        EventResponse event = eventWithSeats(1);
        List<Long> seats = seatIdsOf(event);
        BookingResponse abandoned = hold(new HoldRequest(event.id(), seats));
        ledger.answerNext(TIME_OUT_AFTER_CHARGING);
        assertThatThrownBy(() -> payments.pay(abandoned.id(), new PayRequest(CUSTOMER)))
                .isInstanceOf(PaymentUnavailableException.class);

        clock.advance(PAST_THE_HOLD);
        BookingResponse quicker = hold(new HoldRequest(event.id(), seats));
        reconciler.reconcile();

        BookingResponse refunded = bookings.find(abandoned.id());
        assertThat(refunded.status()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(refunded.payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(bookings.find(quicker.id()).status()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void aRefundTheLedgerDidNotTakeIsPushedAgain() {
        BookingResponse booking = holdOneSeat();
        ledger.beforeCharge(() -> bookings.cancel(booking.id()));
        ledger.failNextRefund();
        assertThatThrownBy(() -> payments.pay(booking.id(), new PayRequest(CUSTOMER)))
                .isInstanceOf(ConflictException.class);
        assertThat(bookings.find(booking.id()).payment().status()).isEqualTo(PaymentStatus.REFUND_PENDING);

        clock.advance(PAST_THE_GRACE_PERIOD);
        reconciler.reconcile();

        BookingResponse refunded = bookings.find(booking.id());
        assertThat(refunded.payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.payment().refundTransferId()).isEqualTo(ledger.refundOf(booking.id()).orElseThrow());
    }

    @Test
    void theSweeperLeavesAHoldWithAChargeInFlightToItsPayment() {
        BookingResponse booking = holdOneSeat();
        ledger.answerNext(UNAVAILABLE);
        assertThatThrownBy(() -> payments.pay(booking.id(), new PayRequest(CUSTOMER)))
                .isInstanceOf(PaymentUnavailableException.class);

        clock.advance(PAST_THE_HOLD);
        sweeper.sweep();
        reconciler.reconcile();

        assertThat(bookings.find(booking.id()).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(ledger.refundOf(booking.id())).isEmpty();
    }

    private BookingResponse holdOneSeat() {
        EventResponse event = eventWithSeats(1);
        return hold(new HoldRequest(event.id(), seatIdsOf(event)));
    }
}
