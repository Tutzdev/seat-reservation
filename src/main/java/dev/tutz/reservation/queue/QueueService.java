package dev.tutz.reservation.queue;

import dev.tutz.reservation.common.BusinessRuleException;
import dev.tutz.reservation.common.ResourceNotFoundException;
import dev.tutz.reservation.event.Event;
import dev.tutz.reservation.event.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class QueueService {

    private final EventRepository events;
    private final WaitingRoom room;
    private final Timer waits;
    private final Clock clock;

    public QueueService(EventRepository events, WaitingRoom room, MeterRegistry meters, Clock clock) {
        this.events = events;
        this.room = room;
        this.clock = clock;
        this.waits = Timer.builder("reservation.waiting_room.wait")
                .description("How long buyers who join a waiting room are told to wait")
                .publishPercentileHistogram()
                .register(meters);
    }

    /** Safe to repeat: a customer already in the room keeps their place until their pass expires. */
    public QueueResponse join(UUID eventId, UUID customerId) {
        Instant now = clock.instant();
        Event event = load(eventId);
        if (event.hasStarted(now)) {
            throw new BusinessRuleException("EVENT_STARTED", "Seats can no longer be held for this event.");
        }
        if (!event.isSaleOpen(now)) {
            throw new BusinessRuleException("SALE_NOT_OPEN",
                    "Sales for this event open at %s.".formatted(event.getSalesOpenAt()));
        }
        if (!event.hasWaitingRoom()) {
            throw new BusinessRuleException("NO_WAITING_ROOM",
                    "This sale has no waiting room: seats can be held right away.");
        }
        QueueTicket ticket = room.join(event, customerId, now);
        waits.record(ticket.waitAt(now));
        return respond(event, ticket, now);
    }

    public QueueResponse status(UUID eventId, UUID customerId) {
        Instant now = clock.instant();
        Event event = load(eventId);
        QueueTicket ticket = event.hasWaitingRoom()
                ? room.ticketOf(event, customerId, now).orElse(null)
                : null;
        if (ticket == null) {
            throw new ResourceNotFoundException("You are not in the waiting room of event %s.".formatted(eventId));
        }
        return respond(event, ticket, now);
    }

    private static QueueResponse respond(Event event, QueueTicket ticket, Instant now) {
        Duration wait = ticket.waitAt(now);
        long interval = event.admissionInterval().toNanos();
        long position = (wait.toNanos() + interval - 1) / interval;
        return new QueueResponse(event.getId(), ticket.isAdmittedAt(now), position, ticket.admittedAt(),
                ticket.passExpiresAt());
    }

    private Event load(UUID eventId) {
        return events.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event %s not found.".formatted(eventId)));
    }
}
