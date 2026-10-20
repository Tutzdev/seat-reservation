package dev.tutz.reservation.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tutz.reservation.Concurrently;
import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.common.BusinessRuleException;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.common.ResourceNotFoundException;
import dev.tutz.reservation.event.CreateEventRequest;
import dev.tutz.reservation.event.EventResponse;
import dev.tutz.reservation.event.SeatRepository;
import dev.tutz.reservation.event.SectionRequest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A customer gives a confirmed booking back: the seats go on sale again and the money goes home. */
class RefundTests extends PostgresTestSupport {

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final Duration PAST_THE_GRACE_PERIOD = Duration.ofMinutes(1).plusSeconds(1);

    @Autowired
    private PaymentService payments;

    @Autowired
    private PaymentReconciler reconciler;

    @Autowired
    private SeatRepository seatRepository;

    @Test
    void aRefundGivesTheMoneyBackAndPutsTheSeatsOnSale() {
        EventResponse event = eventWithSeats(2);
        BookingResponse booking = paidBooking(event);

        BookingResponse refunded = payments.refund(booking.customerId(), booking.id());

        assertThat(refunded.status()).isEqualTo(BookingStatus.REFUNDED);
        assertThat(refunded.payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.payment().refundTransferId()).isEqualTo(ledger.refundOf(booking.id()).orElseThrow());
        assertThat(seatIdsOf(event)).allSatisfy(seat ->
                assertThat(seatRepository.findById(seat).orElseThrow().getBookingId()).isNull());
        assertThat(hold(new HoldRequest(event.id(), seatIdsOf(event))).status()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void askingTwiceRefundsOnce() {
        BookingResponse booking = paidBooking(eventWithSeats(1));

        payments.refund(booking.customerId(), booking.id());
        BookingResponse again = payments.refund(booking.customerId(), booking.id());

        assertThat(again.status()).isEqualTo(BookingStatus.REFUNDED);
        assertThat(ledger.refundCount()).isEqualTo(1);
    }

    @Test
    void concurrentRefundRequestsSendTheMoneyBackOnce() throws Exception {
        BookingResponse booking = paidBooking(eventWithSeats(1));

        List<BookingResponse> outcomes = Concurrently.run(10, () -> payments.refund(booking.customerId(), booking.id()));

        assertThat(outcomes).extracting(BookingResponse::status).containsOnly(BookingStatus.REFUNDED);
        assertThat(ledger.refundCount()).isEqualTo(1);
        assertThat(bookings.find(booking.id()).payment().status()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void aRefundTheLedgerDidNotTakeIsFinishedByTheReconciler() {
        EventResponse event = eventWithSeats(1);
        BookingResponse booking = paidBooking(event);
        ledger.failNextRefund();

        BookingResponse refunded = payments.refund(booking.customerId(), booking.id());

        assertThat(refunded.status()).isEqualTo(BookingStatus.REFUNDED);
        assertThat(refunded.payment().status()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(seatRepository.findById(seatIdsOf(event).getFirst()).orElseThrow().getBookingId()).isNull();

        clock.advance(PAST_THE_GRACE_PERIOD);
        reconciler.reconcile();

        assertThat(bookings.find(booking.id()).payment().status()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(ledger.refundCount()).isEqualTo(1);
    }

    @Test
    void onlyAConfirmedBookingCanBeRefunded() {
        EventResponse event = eventWithSeats(2);
        List<Long> seats = seatIdsOf(event);
        BookingResponse held = hold(new HoldRequest(event.id(), List.of(seats.get(0))));
        BookingResponse cancelled = hold(new HoldRequest(event.id(), List.of(seats.get(1))));
        bookings.cancel(cancelled.customerId(), cancelled.id());

        assertThatThrownBy(() -> payments.refund(held.customerId(), held.id()))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("BOOKING_NOT_CONFIRMED");
        assertThatThrownBy(() -> payments.refund(cancelled.customerId(), cancelled.id()))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("BOOKING_NOT_CONFIRMED");
        assertThat(ledger.refundCount()).isZero();
    }

    @Test
    void refundsCloseADayBeforeTheEvent() {
        BookingResponse booking = paidBooking(eventWithSeats(1)); // the event starts in seven days
        clock.advance(Duration.ofDays(6).plusSeconds(1));

        assertThatThrownBy(() -> payments.refund(booking.customerId(), booking.id()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("REFUND_WINDOW_CLOSED");
        assertThat(bookings.find(booking.id()).status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void aFreeBookingIsGivenBackWithoutTheLedger() {
        EventResponse event = events.create(new CreateEventRequest("Free show " + UUID.randomUUID(), "Park",
                clock.instant().plus(Duration.ofDays(7)), List.of(new SectionRequest("Lawn", List.of("A"), 1, 0))));
        BookingResponse booking = hold(new HoldRequest(event.id(), seatIdsOf(event)));
        payments.pay(booking.customerId(), booking.id(), new PayRequest(ACCOUNT));

        BookingResponse refunded = payments.refund(booking.customerId(), booking.id());

        assertThat(refunded.status()).isEqualTo(BookingStatus.REFUNDED);
        assertThat(refunded.payment()).isNull();
        assertThat(ledger.refundCount()).isZero();
    }

    @Test
    void someoneElsesBookingCannotBeRefunded() {
        BookingResponse booking = paidBooking(eventWithSeats(1));

        assertThatThrownBy(() -> payments.refund(newCustomer().getId(), booking.id()))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(bookings.find(booking.id()).status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void aRefundedBookingCannotBePaidAgain() {
        BookingResponse booking = paidBooking(eventWithSeats(1));
        payments.refund(booking.customerId(), booking.id());

        assertThatThrownBy(() -> payments.pay(booking.customerId(), booking.id(), new PayRequest(ACCOUNT)))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("BOOKING_NOT_ACTIVE");
        assertThat(ledger.chargeCount()).isEqualTo(1);
    }

    private BookingResponse paidBooking(EventResponse event) {
        BookingResponse booking = hold(new HoldRequest(event.id(), seatIdsOf(event)));
        return payments.pay(booking.customerId(), booking.id(), new PayRequest(ACCOUNT));
    }
}
