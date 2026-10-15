package dev.tutz.reservation.booking;

import static dev.tutz.reservation.FakePaymentGateway.Answer.DECLINE;
import static dev.tutz.reservation.FakePaymentGateway.Answer.TIME_OUT_AFTER_CHARGING;
import static dev.tutz.reservation.FakePaymentGateway.Answer.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tutz.reservation.FakePaymentGateway.Charge;
import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.event.CreateEventRequest;
import dev.tutz.reservation.event.EventResponse;
import dev.tutz.reservation.event.SeatResponse;
import dev.tutz.reservation.event.SeatStatus;
import dev.tutz.reservation.event.SectionRequest;
import dev.tutz.reservation.payment.PaymentDeclinedException;
import dev.tutz.reservation.payment.PaymentUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentServiceTests extends PostgresTestSupport {

    private static final UUID CUSTOMER = UUID.randomUUID();
    private static final Duration PAST_THE_HOLD = Duration.ofMinutes(10).plusSeconds(1);

    @Autowired
    private PaymentService payments;

    @Autowired
    private HoldExpirySweeper sweeper;

    @Test
    void payingAHoldChargesTheCustomerAndSellsTheSeats() {
        EventResponse event = eventWithSeats(3);
        List<Long> seats = seatIdsOf(event);
        BookingResponse booking = hold(new HoldRequest(event.id(), seats.subList(0, 2)));

        BookingResponse paid = payments.pay(booking.id(), new PayRequest(CUSTOMER));

        Charge charge = ledger.chargeOf(booking.id()).orElseThrow();
        assertThat(charge.customerAccountId()).isEqualTo(CUSTOMER);
        assertThat(charge.amountCents()).isEqualTo(20_000);
        assertThat(paid.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(paid.payment().status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(paid.payment().transferId()).isEqualTo(charge.transferId());
        assertThat(events.seatMap(event.id(), null)).extracting(SeatResponse::status)
                .containsExactly(SeatStatus.SOLD, SeatStatus.SOLD, SeatStatus.AVAILABLE);
        assertThat(events.find(event.id()).availableSeats()).isEqualTo(1);
    }

    @Test
    void soldSeatsOutliveTheHoldDeadline() {
        EventResponse event = eventWithSeats(1);
        List<Long> seats = seatIdsOf(event);
        BookingResponse booking = hold(new HoldRequest(event.id(), seats));
        payments.pay(booking.id(), new PayRequest(CUSTOMER));

        clock.advance(PAST_THE_HOLD);
        sweeper.sweep();

        assertThat(bookings.find(booking.id()).status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(events.seatMap(event.id(), null)).extracting(SeatResponse::status).containsOnly(SeatStatus.SOLD);
        assertThatThrownBy(() -> hold(new HoldRequest(event.id(), seats)))
                .isInstanceOfSatisfying(ConflictException.class,
                        conflict -> assertThat(conflict.getCode()).isEqualTo("SEATS_UNAVAILABLE"));
    }

    @Test
    void payingAgainReturnsTheConfirmedBookingWithoutChargingTwice() {
        BookingResponse booking = holdOneSeat();

        BookingResponse first = payments.pay(booking.id(), new PayRequest(CUSTOMER));
        BookingResponse second = payments.pay(booking.id(), new PayRequest(CUSTOMER));

        assertThat(second).isEqualTo(first);
        assertThat(ledger.chargeCount()).isEqualTo(1);
    }

    @Test
    void aDeclinedChargeKeepsTheHoldAndCanBeRetriedFromAnotherAccount() {
        BookingResponse booking = holdOneSeat();
        ledger.answerNext(DECLINE);

        assertThatThrownBy(() -> payments.pay(booking.id(), new PayRequest(CUSTOMER)))
                .isInstanceOf(PaymentDeclinedException.class);

        BookingResponse declined = bookings.find(booking.id());
        assertThat(declined.status()).isEqualTo(BookingStatus.HELD);
        assertThat(declined.payment().status()).isEqualTo(PaymentStatus.DECLINED);
        assertThat(declined.payment().declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(ledger.chargeCount()).isZero();

        UUID otherAccount = UUID.randomUUID();
        BookingResponse paid = payments.pay(booking.id(), new PayRequest(otherAccount));

        assertThat(paid.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(paid.payment().customerAccountId()).isEqualTo(otherAccount);
        assertThat(paid.payment().declineReason()).isNull();
    }

    @Test
    void whenTheLedgerDoesNotAnswerThePaymentStaysPendingAndARetrySettlesIt() {
        BookingResponse booking = holdOneSeat();
        ledger.answerNext(UNAVAILABLE);

        assertThatThrownBy(() -> payments.pay(booking.id(), new PayRequest(CUSTOMER)))
                .isInstanceOf(PaymentUnavailableException.class);
        assertThat(bookings.find(booking.id()).payment().status()).isEqualTo(PaymentStatus.PENDING);

        assertThat(payments.pay(booking.id(), new PayRequest(CUSTOMER)).status()).isEqualTo(BookingStatus.CONFIRMED);
    }

    @Test
    void aChargeWhoseAnswerWasLostIsNotChargedAgainOnRetry() {
        BookingResponse booking = holdOneSeat();
        ledger.answerNext(TIME_OUT_AFTER_CHARGING);

        assertThatThrownBy(() -> payments.pay(booking.id(), new PayRequest(CUSTOMER)))
                .isInstanceOf(PaymentUnavailableException.class);
        BookingResponse paid = payments.pay(booking.id(), new PayRequest(CUSTOMER));

        assertThat(paid.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(paid.payment().transferId()).isEqualTo(ledger.chargeOf(booking.id()).orElseThrow().transferId());
        assertThat(ledger.chargeCount()).isEqualTo(1);
    }

    @Test
    void aPendingPaymentCannotBeSwitchedToAnotherAccount() {
        BookingResponse booking = holdOneSeat();
        ledger.answerNext(UNAVAILABLE);
        assertThatThrownBy(() -> payments.pay(booking.id(), new PayRequest(CUSTOMER)))
                .isInstanceOf(PaymentUnavailableException.class);

        assertThatThrownBy(() -> payments.pay(booking.id(), new PayRequest(UUID.randomUUID())))
                .isInstanceOfSatisfying(ConflictException.class,
                        conflict -> assertThat(conflict.getCode()).isEqualTo("PAYMENT_IN_PROGRESS"));
    }

    @Test
    void cancelledAndExpiredHoldsCannotBePaid() {
        BookingResponse cancelled = holdOneSeat();
        bookings.cancel(cancelled.id());
        BookingResponse expired = holdOneSeat();
        clock.advance(PAST_THE_HOLD);

        for (BookingResponse booking : List.of(cancelled, expired)) {
            assertThatThrownBy(() -> payments.pay(booking.id(), new PayRequest(CUSTOMER)))
                    .isInstanceOfSatisfying(ConflictException.class,
                            conflict -> assertThat(conflict.getCode()).isEqualTo("BOOKING_NOT_ACTIVE"));
        }
        assertThat(ledger.chargeCount()).isZero();
    }

    @Test
    void aConfirmedBookingCannotBeCancelled() {
        BookingResponse booking = holdOneSeat();
        payments.pay(booking.id(), new PayRequest(CUSTOMER));

        assertThatThrownBy(() -> bookings.cancel(booking.id()))
                .isInstanceOfSatisfying(ConflictException.class,
                        conflict -> assertThat(conflict.getCode()).isEqualTo("BOOKING_NOT_ACTIVE"));
    }

    @Test
    void freeSeatsAreConfirmedWithoutCallingTheLedger() {
        EventResponse event = events.create(new CreateEventRequest("Free rehearsal " + UUID.randomUUID(), "Test venue",
                clock.instant().plus(Duration.ofDays(7)), List.of(new SectionRequest("Floor", List.of("A"), 2, 0))));
        BookingResponse booking = hold(new HoldRequest(event.id(), seatIdsOf(event)));

        BookingResponse confirmed = payments.pay(booking.id(), new PayRequest(CUSTOMER));

        assertThat(confirmed.status()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(confirmed.payment()).isNull();
        assertThat(ledger.chargeCount()).isZero();
        assertThat(events.seatMap(event.id(), null)).extracting(SeatResponse::status).containsOnly(SeatStatus.SOLD);
    }

    private BookingResponse holdOneSeat() {
        EventResponse event = eventWithSeats(1);
        return hold(new HoldRequest(event.id(), seatIdsOf(event)));
    }
}
