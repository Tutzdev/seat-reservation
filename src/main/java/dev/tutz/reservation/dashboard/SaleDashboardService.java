package dev.tutz.reservation.dashboard;

import dev.tutz.reservation.common.ResourceNotFoundException;
import dev.tutz.reservation.dashboard.SaleCounters.HoldCounts;
import dev.tutz.reservation.dashboard.SaleDashboardResponse.Holds;
import dev.tutz.reservation.dashboard.SaleDashboardResponse.WaitingLine;
import dev.tutz.reservation.event.Event;
import dev.tutz.reservation.event.EventRepository;
import dev.tutz.reservation.event.SeatRepository;
import dev.tutz.reservation.queue.WaitingRoom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SaleDashboardService {

    private final EventRepository events;
    private final SeatRepository seats;
    private final SaleCounters counters;
    private final WaitingRoom room;
    private final Clock clock;

    public SaleDashboardService(EventRepository events, SeatRepository seats, SaleCounters counters, WaitingRoom room,
            Clock clock) {
        this.events = events;
        this.seats = seats;
        this.counters = counters;
        this.room = room;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SaleDashboardResponse of(UUID eventId) {
        Instant now = clock.instant();
        Event event = events.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event %s not found.".formatted(eventId)));
        HoldCounts holds = counters.of(eventId);
        return new SaleDashboardResponse(event.getId(), event.getSalesOpenAt(), seats.countSeats(eventId, now),
                new Holds(holds.won(), holds.lost()), line(event, now));
    }

    private WaitingLine line(Event event, Instant now) {
        if (!event.hasWaitingRoom()) {
            return null;
        }
        Instant clearsAt = room.lineClearsAt(event, now).orElse(null);
        long interval = event.admissionInterval().toNanos();
        long waiting = clearsAt == null ? 0 : (Duration.between(now, clearsAt).toNanos() + interval - 1) / interval;
        return new WaitingLine(event.getAdmitsPerMinute(), waiting, clearsAt);
    }
}
