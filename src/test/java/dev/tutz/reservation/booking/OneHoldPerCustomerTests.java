package dev.tutz.reservation.booking;

import static dev.tutz.reservation.FakePaymentGateway.Answer.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tutz.reservation.Concurrently;
import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.event.EventResponse;
import dev.tutz.reservation.event.SeatRepository;
import dev.tutz.reservation.payment.PaymentUnavailableException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A customer holds seats for an event through one booking at a time. */
class OneHoldPerCustomerTests extends PostgresTestSupport {

    private static final UUID ACCOUNT = UUID.randomUUID();
    private static final Duration PAST_THE_HOLD = Duration.ofMinutes(11);

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private PaymentService payments;

    @Test
    void aSecondHoldForTheSameEventPointsToTheFirstOne() {
        EventResponse event = eventWithSeats(2);
        List<Long> seats = seatIdsOf(event);
        UUID customer = newCustomer().getId();
        BookingResponse first = hold(customer, new HoldRequest(event.id(), List.of(seats.get(0))));

        assertThatThrownBy(() -> hold(customer, new HoldRequest(event.id(), List.of(seats.get(1)))))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> {
                    assertThat(conflict.getCode()).isEqualTo("HOLD_ALREADY_ACTIVE");
                    assertThat(conflict.getDetails()).isEqualTo(Map.of("bookingId", first.id()));
                });
        assertThat(seatRepository.findById(seats.get(1)).orElseThrow().getBookingId()).isNull();
    }

    @Test
    void holdsForDifferentEventsDoNotCount() {
        UUID customer = newCustomer().getId();
        EventResponse show = eventWithSeats(1);
        EventResponse otherShow = eventWithSeats(1);

        hold(customer, new HoldRequest(show.id(), seatIdsOf(show)));

        assertThat(hold(customer, new HoldRequest(otherShow.id(), seatIdsOf(otherShow))).status())
                .isEqualTo(BookingStatus.HELD);
    }

    @Test
    void aCancelledOrPaidHoldLetsTheCustomerHoldAgain() {
        EventResponse event = eventWithSeats(3);
        List<Long> seats = seatIdsOf(event);
        UUID customer = newCustomer().getId();

        BookingResponse cancelled = hold(customer, new HoldRequest(event.id(), List.of(seats.get(0))));
        bookings.cancel(customer, cancelled.id());
        BookingResponse paid = hold(customer, new HoldRequest(event.id(), List.of(seats.get(1))));
        payments.pay(customer, paid.id(), new PayRequest(ACCOUNT));

        assertThat(hold(customer, new HoldRequest(event.id(), List.of(seats.get(2)))).status())
                .isEqualTo(BookingStatus.HELD);
    }

    @Test
    void anExpiredHoldIsReplacedWithoutWaitingForTheSweeper() {
        EventResponse event = eventWithSeats(2);
        List<Long> seats = seatIdsOf(event);
        UUID customer = newCustomer().getId();
        BookingResponse abandoned = hold(customer, new HoldRequest(event.id(), seats));
        clock.advance(PAST_THE_HOLD);

        BookingResponse next = hold(customer, new HoldRequest(event.id(), List.of(seats.get(1))));

        assertThat(next.status()).isEqualTo(BookingStatus.HELD);
        assertThat(bookingRepository.findById(abandoned.id()).orElseThrow().getStatus()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(seatRepository.findById(seats.get(0)).orElseThrow().getBookingId()).isNull();
        assertThat(seatRepository.findById(seats.get(1)).orElseThrow().getBookingId()).isEqualTo(next.id());
    }

    @Test
    void anExpiredHoldWithAChargeInFlightStillCounts() {
        EventResponse event = eventWithSeats(2);
        List<Long> seats = seatIdsOf(event);
        UUID customer = newCustomer().getId();
        BookingResponse paying = hold(customer, new HoldRequest(event.id(), List.of(seats.get(0))));
        ledger.answerNext(UNAVAILABLE);
        assertThatThrownBy(() -> payments.pay(customer, paying.id(), new PayRequest(ACCOUNT)))
                .isInstanceOf(PaymentUnavailableException.class);
        clock.advance(PAST_THE_HOLD);

        assertThatThrownBy(() -> hold(customer, new HoldRequest(event.id(), List.of(seats.get(1)))))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("HOLD_ALREADY_ACTIVE");
        assertThat(bookingRepository.findById(paying.id()).orElseThrow().getStatus()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void concurrentHoldsOfOneCustomerEndWithOneBooking() throws Exception {
        EventResponse event = eventWithSeats(20);
        UUID customer = newCustomer().getId();
        List<Callable<Optional<BookingResponse>>> tasks = seatIdsOf(event).stream()
                .<Callable<Optional<BookingResponse>>>map(seat -> () -> tryHold(customer, event.id(), seat))
                .toList();

        List<BookingResponse> winners = Concurrently.run(tasks).stream().flatMap(Optional::stream).toList();

        assertThat(winners).hasSize(1);
        assertThat(seatIdsOf(event)).filteredOn(seat -> seatRepository.findById(seat).orElseThrow().getBookingId() != null)
                .containsExactly(winners.getFirst().seats().getFirst().seatId());
    }

    private Optional<BookingResponse> tryHold(UUID customer, UUID eventId, long seatId) {
        try {
            return Optional.of(hold(customer, new HoldRequest(eventId, List.of(seatId))));
        } catch (ConflictException conflict) {
            assertThat(conflict.getCode()).isEqualTo("HOLD_ALREADY_ACTIVE");
            return Optional.empty();
        }
    }
}
