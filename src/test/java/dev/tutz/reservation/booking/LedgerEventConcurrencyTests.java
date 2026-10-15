package dev.tutz.reservation.booking;

import static dev.tutz.reservation.FakePaymentGateway.Answer.TIME_OUT_AFTER_CHARGING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tutz.reservation.Concurrently;
import dev.tutz.reservation.FakePaymentGateway.Charge;
import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.event.EventResponse;
import dev.tutz.reservation.event.Seat;
import dev.tutz.reservation.event.SeatRepository;
import dev.tutz.reservation.event.SeatStatus;
import dev.tutz.reservation.payment.PaymentUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The ledger's event racing every other path that settles the same payment: the customer's retry, the
 * reconciler and a cancel. Whoever locks the booking first settles it; the other finds it settled.
 */
class LedgerEventConcurrencyTests extends PostgresTestSupport {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final UUID MERCHANT = UUID.fromString("00000000-0000-0000-0000-00000000c0de");
    private static final Duration PAST_THE_GRACE_PERIOD = Duration.ofMinutes(1).plusSeconds(1);

    @Autowired
    private PaymentService payments;

    @Autowired
    private PaymentReconciler reconciler;

    @Autowired
    private LedgerEventHandler handler;

    @Autowired
    private LedgerInboxRepository inbox;

    @Autowired
    private SeatRepository seatRepository;

    @Test
    void anEventRacingTheCustomersRetryCapturesTheChargeOnce() throws Exception {
        for (int round = 0; round < 15; round++) {
            BookingResponse booking = holdOneSeat();
            payWithLostAnswer(booking.id());
            Charge charge = ledger.chargeOf(booking.id()).orElseThrow();

            Concurrently.run(List.<Callable<Object>>of(
                    () -> payments.pay(booking.id(), new PayRequest(CUSTOMER)),
                    () -> handler.handle(chargeEvent(booking.id(), charge))));

            assertCapturedOnce(booking.id(), charge);
        }
    }

    @Test
    void anEventRacingTheReconcilerCapturesTheChargeOnce() throws Exception {
        for (int round = 0; round < 15; round++) {
            clock.reset();
            BookingResponse booking = holdOneSeat();
            payWithLostAnswer(booking.id());
            Charge charge = ledger.chargeOf(booking.id()).orElseThrow();
            clock.advance(PAST_THE_GRACE_PERIOD);

            Concurrently.run(List.<Callable<Object>>of(
                    () -> {
                        reconciler.reconcile();
                        return null;
                    },
                    () -> handler.handle(chargeEvent(booking.id(), charge))));

            assertCapturedOnce(booking.id(), charge);
        }
    }

    @Test
    void anEventRacingACancelEitherSellsTheSeatOrRefundsTheCharge() throws Exception {
        for (int round = 0; round < 15; round++) {
            EventResponse event = eventWithSeats(1);
            Long seatId = seatIdsOf(event).getFirst();
            BookingResponse booking = hold(new HoldRequest(event.id(), List.of(seatId)));
            payWithLostAnswer(booking.id());
            Charge charge = ledger.chargeOf(booking.id()).orElseThrow();

            Concurrently.run(List.<Callable<Object>>of(
                    () -> handler.handle(chargeEvent(booking.id(), charge)),
                    () -> ignoringConflicts(() -> bookings.cancel(booking.id()))));

            BookingResponse outcome = bookings.find(booking.id());
            Seat seat = seatRepository.findById(seatId).orElseThrow();
            if (outcome.status() == BookingStatus.CONFIRMED) {
                assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.CAPTURED);
                assertThat(seat.getStatus()).isEqualTo(SeatStatus.SOLD);
                assertThat(ledger.refundOf(booking.id())).isEmpty();
            } else {
                // The cancel won, so the money that already moved goes back.
                assertThat(outcome.status()).isEqualTo(BookingStatus.CANCELLED);
                assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.REFUNDED);
                assertThat(outcome.payment().refundTransferId()).isEqualTo(ledger.refundOf(booking.id()).orElseThrow());
                assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
            }
            assertThat(inbox.findByBookingId(booking.id())).hasSize(1);
        }
    }

    private void assertCapturedOnce(UUID bookingId, Charge charge) {
        BookingResponse outcome = bookings.find(bookingId);
        assertThat(outcome.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(outcome.payment().status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(outcome.payment().transferId()).isEqualTo(charge.transferId());
        assertThat(ledger.chargeOf(bookingId)).contains(charge);
        assertThat(ledger.refundOf(bookingId)).isEmpty();
        assertThat(inbox.findByBookingId(bookingId)).hasSize(1);
    }

    private BookingResponse holdOneSeat() {
        EventResponse event = eventWithSeats(1);
        return hold(new HoldRequest(event.id(), seatIdsOf(event)));
    }

    /** The ledger posts the charge, but the answer never comes back: the payment stays PENDING. */
    private void payWithLostAnswer(UUID bookingId) {
        ledger.answerNext(TIME_OUT_AFTER_CHARGING);
        assertThatThrownBy(() -> payments.pay(bookingId, new PayRequest(CUSTOMER)))
                .isInstanceOf(PaymentUnavailableException.class);
    }

    private static LedgerTransfer chargeEvent(UUID bookingId, Charge charge) {
        return new LedgerTransfer(UUID.randomUUID(), "TRANSFER", charge.transferId(), charge.customerAccountId(),
                MERCHANT, null, "booking-" + bookingId);
    }

    /** Returns the result, or the conflict code when the other side of the race won. */
    private static Object ignoringConflicts(Callable<?> action) throws Exception {
        try {
            return action.call();
        } catch (ConflictException conflict) {
            return conflict.getCode();
        }
    }
}
