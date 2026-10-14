package dev.tutz.reservation.event;

import dev.tutz.reservation.common.PageResponse;
import dev.tutz.reservation.common.ResourceNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

    private final EventRepository events;
    private final SeatRepository seats;
    private final EventProperties properties;
    private final Clock clock;

    public EventService(EventRepository events, SeatRepository seats, EventProperties properties, Clock clock) {
        this.events = events;
        this.seats = seats;
        this.properties = properties;
        this.clock = clock;
    }

    /** The event and every one of its seats are created in the same transaction: no event without a seat map. */
    @Transactional
    public EventResponse create(CreateEventRequest request) {
        Instant now = clock.instant();
        SeatLayout layout = SeatLayout.of(request.sections(), properties.maxSeatsPerEvent());
        Event event = events.save(Event.schedule(request.name(), request.venue(), request.startsAt(), now));
        seats.saveAll(layout.seatsFor(event.getId()));

        return EventResponse.from(event, seats.summarizeSections(event.getId(), now));
    }

    @Transactional(readOnly = true)
    public EventResponse find(UUID eventId) {
        Event event = load(eventId);
        return EventResponse.from(event, seats.summarizeSections(eventId, clock.instant()));
    }

    @Transactional(readOnly = true)
    public PageResponse<EventSummaryResponse> upcoming(Pageable pageable) {
        return PageResponse.from(events.findByStartsAtAfter(clock.instant(), pageable).map(EventSummaryResponse::from));
    }

    @Transactional(readOnly = true)
    public List<SeatResponse> seatMap(UUID eventId, String section) {
        load(eventId);
        List<Seat> result = section == null
                ? seats.findByEventIdOrderById(eventId)
                : seats.findByEventIdAndSectionOrderById(eventId, section.strip());

        Instant now = clock.instant();
        return result.stream().map(seat -> SeatResponse.from(seat, now)).toList();
    }

    private Event load(UUID eventId) {
        return events.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event %s not found.".formatted(eventId)));
    }
}
