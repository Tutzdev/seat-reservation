package dev.tutz.reservation.booking;

import dev.tutz.reservation.common.BusinessRuleException;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.common.ResourceNotFoundException;
import dev.tutz.reservation.event.Event;
import dev.tutz.reservation.event.EventRepository;
import dev.tutz.reservation.event.Seat;
import dev.tutz.reservation.event.SeatRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The transactional part of a hold. Idempotency is handled around it by {@link BookingService}. */
@Component
class SeatHolder {

    private final BookingRepository bookings;
    private final EventRepository events;
    private final SeatRepository seats;
    private final BookingProperties properties;
    private final Clock clock;

    SeatHolder(BookingRepository bookings, EventRepository events, SeatRepository seats,
            BookingProperties properties, Clock clock) {
        this.bookings = bookings;
        this.events = events;
        this.seats = seats;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Holds every requested seat or none of them. Each seat is taken with its own conditional UPDATE,
     * always in ascending id order: two requests for overlapping seats lock rows in the same order,
     * so one waits for the other instead of deadlocking. If any seat is taken, the exception rolls back
     * the whole transaction, including the seats this request already got.
     *
     * <p>The booking row is inserted before any seat is touched. A concurrent retry with the same
     * Idempotency-Key therefore blocks on the unique index and never competes for the seats.
     */
    @Transactional
    BookingResponse hold(HoldCommand command) {
        Instant now = clock.instant();
        Event event = events.findById(command.eventId())
                .orElseThrow(() -> new ResourceNotFoundException("Event %s not found.".formatted(command.eventId())));
        if (event.hasStarted(now)) {
            throw new BusinessRuleException("EVENT_STARTED", "Seats can no longer be held for this event.");
        }

        List<Long> seatIds = validated(command.seatIds());
        List<Seat> requested = seats.findByEventIdAndIdIn(event.getId(), seatIds);
        if (requested.size() != seatIds.size()) {
            throw new BusinessRuleException("UNKNOWN_SEAT", "Every seat must belong to event %s.".formatted(event.getId()));
        }

        Booking booking = bookings.saveAndFlush(Booking.hold(command, requested, now, properties.holdDuration()));

        List<Long> unavailable = new ArrayList<>();
        for (Long seatId : seatIds) {
            if (seats.hold(seatId, booking.getId(), booking.getExpiresAt(), now) == 0) {
                unavailable.add(seatId);
            }
        }
        if (!unavailable.isEmpty()) {
            throw new ConflictException("SEATS_UNAVAILABLE", "Some of the requested seats are no longer available.",
                    Map.of("unavailableSeatIds", unavailable));
        }
        return BookingResponse.from(booking, now);
    }

    private List<Long> validated(List<Long> seatIds) {
        if (new HashSet<>(seatIds).size() != seatIds.size()) {
            throw new BusinessRuleException("DUPLICATE_SEAT", "A seat can appear only once in a booking.");
        }
        if (seatIds.size() > properties.maxSeatsPerBooking()) {
            throw new BusinessRuleException("TOO_MANY_SEATS",
                    "A booking can hold at most %d seats.".formatted(properties.maxSeatsPerBooking()));
        }
        return seatIds.stream().sorted().toList();
    }
}
