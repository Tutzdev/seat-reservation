package dev.tutz.reservation.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.event.EventResponse;
import dev.tutz.reservation.event.Seat;
import dev.tutz.reservation.event.SeatRepository;
import dev.tutz.reservation.event.SeatResponse;
import dev.tutz.reservation.event.SeatStatus;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HoldExpiryTests extends PostgresTestSupport {

    private static final Duration PAST_THE_HOLD = Duration.ofMinutes(10).plusSeconds(1);

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private SeatRepository seatRepository;

    @Autowired
    private HoldExpirySweeper sweeper;

    @Test
    void anExpiredHoldFreesItsSeatsBeforeTheSweeperRuns() {
        EventResponse event = eventWithSeats(2);
        List<Long> seats = seatIdsOf(event);
        BookingResponse abandoned = hold(new HoldRequest(event.id(), seats));

        clock.advance(PAST_THE_HOLD);

        assertThat(bookings.find(abandoned.id()).status()).isEqualTo(BookingStatus.EXPIRED);
        assertThat(events.seatMap(event.id(), null)).extracting(SeatResponse::status).containsOnly(SeatStatus.AVAILABLE);
        assertThat(events.find(event.id()).availableSeats()).isEqualTo(2);

        BookingResponse next = hold(new HoldRequest(event.id(), List.of(seats.getFirst())));
        assertThat(next.status()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void sweeperExpiresAbandonedHoldsAndReleasesTheirSeats() {
        EventResponse event = eventWithSeats(2);
        List<Long> seats = seatIdsOf(event);
        BookingResponse abandoned = hold(new HoldRequest(event.id(), seats));
        clock.advance(PAST_THE_HOLD);

        sweeper.sweep();

        assertThat(bookingRepository.findById(abandoned.id()).orElseThrow().getStatus()).isEqualTo(BookingStatus.EXPIRED);
        for (Long seatId : seats) {
            Seat seat = seatRepository.findById(seatId).orElseThrow();
            assertThat(seat.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
            assertThat(seat.getBookingId()).isNull();
        }
    }

    @Test
    void sweeperDoesNotTouchASeatThatAnotherBookingAlreadyTookOver() {
        EventResponse event = eventWithSeats(1);
        List<Long> seats = seatIdsOf(event);
        BookingResponse abandoned = hold(new HoldRequest(event.id(), seats));
        clock.advance(PAST_THE_HOLD);
        BookingResponse next = hold(new HoldRequest(event.id(), seats));

        sweeper.sweep();

        assertThat(bookingRepository.findById(abandoned.id()).orElseThrow().getStatus()).isEqualTo(BookingStatus.EXPIRED);
        Seat seat = seatRepository.findById(seats.getFirst()).orElseThrow();
        assertThat(seat.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(seat.getBookingId()).isEqualTo(next.id());
    }

    @Test
    void sweeperLeavesActiveHoldsAlone() {
        EventResponse event = eventWithSeats(1);
        BookingResponse active = hold(new HoldRequest(event.id(), seatIdsOf(event)));
        clock.advance(Duration.ofMinutes(9));

        sweeper.sweep();

        assertThat(bookingRepository.findById(active.id()).orElseThrow().getStatus()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void anExpiredHoldCannotBeCancelled() {
        EventResponse event = eventWithSeats(1);
        BookingResponse abandoned = hold(new HoldRequest(event.id(), seatIdsOf(event)));
        clock.advance(PAST_THE_HOLD);

        assertThatThrownBy(() -> bookings.cancel(abandoned.id()))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> {
                    assertThat(conflict.getCode()).isEqualTo("BOOKING_NOT_ACTIVE");
                    assertThat(conflict.getDetails()).containsEntry("bookingStatus", BookingStatus.EXPIRED);
                });
    }
}
