package dev.tutz.reservation.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.event.EventResponse;
import dev.tutz.reservation.event.Seat;
import dev.tutz.reservation.event.SeatRepository;
import dev.tutz.reservation.event.SeatStatus;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What happens when the world changes while the ledger call is in flight. The fake ledger runs a step
 * right before it charges, standing in for everything that can happen during a slow payment.
 */
class LatePaymentTests extends PostgresTestSupport {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final Duration PAST_THE_HOLD = Duration.ofMinutes(10).plusSeconds(1);

    @Autowired
    private PaymentService payments;

    @Autowired
    private SeatRepository seatRepository;

    @Test
    void aPaymentThatLandsAfterTheDeadlineStillSellsSeatsNobodyTook() {
        EventResponse event = eventWithSeats(2);
        BookingResponse booking = hold(new HoldRequest(event.id(), seatIdsOf(event)));
        ledger.beforeCharge(() -> clock.advance(PAST_THE_HOLD));

        BookingResponse paid = payments.pay(booking.customerId(), booking.id(), new PayRequest(CUSTOMER));

        assertThat(paid.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(ledger.refundCount()).isZero();
    }

    @Test
    void aPaymentThatLandsAfterASeatWasResoldIsRefundedAndFreesTheOtherSeats() {
        EventResponse event = eventWithSeats(2);
        List<Long> seats = seatIdsOf(event);
        BookingResponse late = hold(new HoldRequest(event.id(), seats));
        AtomicReference<BookingResponse> quicker = new AtomicReference<>();
        ledger.beforeCharge(() -> {
            clock.advance(PAST_THE_HOLD);
            quicker.set(hold(new HoldRequest(event.id(), List.of(seats.get(1)))));
        });

        assertThatThrownBy(() -> payments.pay(late.customerId(), late.id(), new PayRequest(CUSTOMER)))
                .isInstanceOfSatisfying(ConflictException.class,
                        conflict -> assertThat(conflict.getCode()).isEqualTo("PAYMENT_REFUNDED"));

        BookingResponse refunded = bookings.find(late.id());
        assertThat(refunded.status()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(refunded.payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.payment().transferId()).isEqualTo(ledger.chargeOf(late.id()).orElseThrow().transferId());
        assertThat(refunded.payment().refundTransferId()).isEqualTo(ledger.refundOf(late.id()).orElseThrow());

        Seat stillFree = seatRepository.findById(seats.get(0)).orElseThrow();
        assertThat(stillFree.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(stillFree.getBookingId()).isNull();
        Seat resold = seatRepository.findById(seats.get(1)).orElseThrow();
        assertThat(resold.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(resold.getBookingId()).isEqualTo(quicker.get().id());
    }

    @Test
    void cancellingWhileTheChargeIsInFlightRefundsIt() {
        EventResponse event = eventWithSeats(1);
        BookingResponse booking = hold(new HoldRequest(event.id(), seatIdsOf(event)));
        ledger.beforeCharge(() -> bookings.cancel(booking.customerId(), booking.id()));

        assertThatThrownBy(() -> payments.pay(booking.customerId(), booking.id(), new PayRequest(CUSTOMER)))
                .isInstanceOfSatisfying(ConflictException.class,
                        conflict -> assertThat(conflict.getCode()).isEqualTo("PAYMENT_REFUNDED"));

        BookingResponse cancelled = bookings.find(booking.id());
        assertThat(cancelled.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(events.find(event.id()).availableSeats()).isEqualTo(1);
    }

    @Test
    void aRefundTheLedgerCouldNotTakeIsKeptAndPushedAgainOnTheNextCall() {
        EventResponse event = eventWithSeats(1);
        BookingResponse booking = hold(new HoldRequest(event.id(), seatIdsOf(event)));
        ledger.beforeCharge(() -> bookings.cancel(booking.customerId(), booking.id()));
        ledger.failNextRefund();

        assertThatThrownBy(() -> payments.pay(booking.customerId(), booking.id(), new PayRequest(CUSTOMER)))
                .isInstanceOf(ConflictException.class);
        assertThat(bookings.find(booking.id()).payment().status()).isEqualTo(PaymentStatus.REFUND_PENDING);

        assertThatThrownBy(() -> payments.pay(booking.customerId(), booking.id(), new PayRequest(CUSTOMER)))
                .isInstanceOf(ConflictException.class);
        assertThat(bookings.find(booking.id()).payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(ledger.chargeCount()).isEqualTo(1);
        assertThat(ledger.refundCount()).isEqualTo(1);
    }
}
