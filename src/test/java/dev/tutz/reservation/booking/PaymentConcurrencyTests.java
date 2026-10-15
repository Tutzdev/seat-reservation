package dev.tutz.reservation.booking;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tutz.reservation.Concurrently;
import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.event.EventResponse;
import dev.tutz.reservation.event.Seat;
import dev.tutz.reservation.event.SeatRepository;
import dev.tutz.reservation.event.SeatStatus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Payments racing each other, cancels and other customers, all released by the same latch. */
class PaymentConcurrencyTests extends PostgresTestSupport {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final Duration PAST_THE_HOLD = Duration.ofMinutes(10).plusSeconds(1);

    @Autowired
    private PaymentService payments;

    @Autowired
    private SeatRepository seatRepository;

    @Test
    void twentySimultaneousPaymentsForOneBookingChargeOnce() throws Exception {
        EventResponse event = eventWithSeats(2);
        BookingResponse booking = hold(new HoldRequest(event.id(), seatIdsOf(event)));

        List<BookingResponse> outcomes = Concurrently.run(20, () -> payments.pay(booking.id(), new PayRequest(CUSTOMER)));

        assertThat(outcomes).extracting(BookingResponse::status).containsOnly(BookingStatus.CONFIRMED);
        assertThat(outcomes).extracting(outcome -> outcome.payment().transferId())
                .containsOnly(ledger.chargeOf(booking.id()).orElseThrow().transferId());
        assertThat(ledger.chargeCount()).isEqualTo(1);
    }

    @Test
    void aPaymentRacingACancelEitherSellsTheSeatOrRefundsTheCharge() throws Exception {
        for (int round = 0; round < 15; round++) {
            EventResponse event = eventWithSeats(1);
            Long seatId = seatIdsOf(event).getFirst();
            BookingResponse booking = hold(new HoldRequest(event.id(), List.of(seatId)));

            Concurrently.run(List.<Callable<Object>>of(
                    () -> ignoringConflicts(() -> payments.pay(booking.id(), new PayRequest(CUSTOMER))),
                    () -> ignoringConflicts(() -> bookings.cancel(booking.id()))));

            BookingResponse outcome = bookings.find(booking.id());
            Seat seat = seatRepository.findById(seatId).orElseThrow();
            if (outcome.status() == BookingStatus.CONFIRMED) {
                assertThat(seat.getStatus()).isEqualTo(SeatStatus.SOLD);
                assertThat(ledger.refundOf(booking.id())).isEmpty();
            } else {
                assertThat(outcome.status()).isEqualTo(BookingStatus.CANCELLED);
                assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
                // Either the cancel won before the charge started, or the charge was given back.
                assertThat(outcome.payment() == null || outcome.payment().status() == PaymentStatus.DECLINED
                        || outcome.payment().status() == PaymentStatus.REFUNDED).isTrue();
                assertThat(ledger.chargeOf(booking.id()).isPresent()).isEqualTo(ledger.refundOf(booking.id()).isPresent());
            }
        }
    }

    @Test
    void aLatePaymentRacingANewHoldNeverLeavesTheSeatWithBoth() throws Exception {
        for (int round = 0; round < 15; round++) {
            clock.reset();
            EventResponse event = eventWithSeats(1);
            List<Long> seats = seatIdsOf(event);
            BookingResponse late = hold(new HoldRequest(event.id(), seats));
            // The deadline passes while the charge is in flight; the other customer tries the seat right then,
            // racing the UPDATE that sells it.
            CountDownLatch deadlinePassed = new CountDownLatch(1);
            ledger.beforeCharge(() -> {
                clock.advance(PAST_THE_HOLD);
                deadlinePassed.countDown();
            });

            List<Object> outcomes = Concurrently.run(List.<Callable<Object>>of(
                    () -> ignoringConflicts(() -> payments.pay(late.id(), new PayRequest(CUSTOMER))),
                    () -> {
                        deadlinePassed.await();
                        return ignoringConflicts(() -> bookings.hold(new HoldRequest(event.id(), seats), newKey()).booking());
                    }));

            Seat seat = seatRepository.findById(seats.getFirst()).orElseThrow();
            BookingResponse lateOutcome = bookings.find(late.id());
            if (lateOutcome.status() == BookingStatus.CONFIRMED) {
                assertThat(seat.getStatus()).isEqualTo(SeatStatus.SOLD);
                assertThat(seat.getBookingId()).isEqualTo(late.id());
                assertThat(outcomes.get(1)).isEqualTo("SEATS_UNAVAILABLE");
            } else {
                assertThat(lateOutcome.payment().status()).isEqualTo(PaymentStatus.REFUNDED);
                assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
                assertThat(seat.getBookingId()).isEqualTo(((BookingResponse) outcomes.get(1)).id());
            }
        }
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
