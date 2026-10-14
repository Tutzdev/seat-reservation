package dev.tutz.reservation.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.common.BusinessRuleException;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.event.EventResponse;
import dev.tutz.reservation.event.SeatResponse;
import dev.tutz.reservation.event.SeatStatus;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BookingServiceTests extends PostgresTestSupport {

    @Test
    void holdsTheRequestedSeatsForTenMinutes() {
        EventResponse event = eventWithSeats(4);
        List<Long> seats = seatIdsOf(event);

        BookingResponse booking = hold(new HoldRequest(event.id(), List.of(seats.get(1), seats.get(0))));

        assertThat(booking.status()).isEqualTo(BookingStatus.HELD);
        assertThat(booking.totalCents()).isEqualTo(20_000);
        assertThat(booking.expiresAt()).isEqualTo(booking.createdAt().plus(Duration.ofMinutes(10)));
        assertThat(booking.seats()).extracting(BookingResponse.SeatLine::seatId).containsExactly(seats.get(0), seats.get(1));
        assertThat(events.seatMap(event.id(), null)).extracting(SeatResponse::status)
                .containsExactly(SeatStatus.HELD, SeatStatus.HELD, SeatStatus.AVAILABLE, SeatStatus.AVAILABLE);
        assertThat(events.find(event.id()).availableSeats()).isEqualTo(2);
        assertThat(bookings.find(booking.id())).isEqualTo(booking);
    }

    @Test
    void holdsAllTheSeatsOrNoneOfThem() {
        EventResponse event = eventWithSeats(3);
        List<Long> seats = seatIdsOf(event);
        hold(new HoldRequest(event.id(), List.of(seats.get(1))));

        assertThatThrownBy(() -> hold(new HoldRequest(event.id(), seats)))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> {
                    assertThat(conflict.getCode()).isEqualTo("SEATS_UNAVAILABLE");
                    assertThat(conflict.getDetails()).containsEntry("unavailableSeatIds", List.of(seats.get(1)));
                });
        assertThat(events.seatMap(event.id(), null)).extracting(SeatResponse::status)
                .containsExactly(SeatStatus.AVAILABLE, SeatStatus.HELD, SeatStatus.AVAILABLE);
    }

    @Test
    void cancellingReleasesTheSeatsForTheNextBuyer() {
        EventResponse event = eventWithSeats(2);
        List<Long> seats = seatIdsOf(event);
        BookingResponse first = hold(new HoldRequest(event.id(), seats));

        BookingResponse cancelled = bookings.cancel(first.id());

        assertThat(cancelled.status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.seats()).hasSize(2);
        assertThat(events.seatMap(event.id(), null)).extracting(SeatResponse::status).containsOnly(SeatStatus.AVAILABLE);
        assertThat(hold(new HoldRequest(event.id(), seats)).status()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void cancellingTwiceIsHarmless() {
        EventResponse event = eventWithSeats(1);
        List<Long> seats = seatIdsOf(event);
        BookingResponse first = hold(new HoldRequest(event.id(), seats));
        bookings.cancel(first.id());
        BookingResponse second = hold(new HoldRequest(event.id(), seats));

        assertThat(bookings.cancel(first.id()).status()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(bookings.find(second.id()).status()).isEqualTo(BookingStatus.HELD);
        assertThat(events.seatMap(event.id(), null)).extracting(SeatResponse::status).containsOnly(SeatStatus.HELD);
    }

    @Test
    void rejectsMoreSeatsThanOneBookingMayHold() {
        EventResponse event = eventWithSeats(7);

        assertThatThrownBy(() -> hold(new HoldRequest(event.id(), seatIdsOf(event))))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("TOO_MANY_SEATS");
    }

    @Test
    void rejectsTheSameSeatTwiceInOneRequest() {
        EventResponse event = eventWithSeats(1);
        Long seat = seatIdsOf(event).getFirst();

        assertThatThrownBy(() -> hold(new HoldRequest(event.id(), List.of(seat, seat))))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("DUPLICATE_SEAT");
    }

    @Test
    void rejectsASeatFromAnotherEvent() {
        EventResponse event = eventWithSeats(1);
        Long otherEventSeat = seatIdsOf(eventWithSeats(1)).getFirst();

        assertThatThrownBy(() -> hold(new HoldRequest(event.id(), List.of(otherEventSeat))))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("UNKNOWN_SEAT");
    }

    @Test
    void seatsCannotBeHeldOnceTheEventStarted() {
        EventResponse event = eventWithSeats(1);
        clock.advance(Duration.ofDays(8));

        assertThatThrownBy(() -> hold(new HoldRequest(event.id(), seatIdsOf(event))))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("EVENT_STARTED");
    }
}
