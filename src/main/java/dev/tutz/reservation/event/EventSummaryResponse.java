package dev.tutz.reservation.event;

import java.time.Instant;
import java.util.UUID;

public record EventSummaryResponse(UUID id, String name, String venue, Instant startsAt, Instant salesOpenAt,
        Integer admitsPerMinute) {

    static EventSummaryResponse from(Event event) {
        return new EventSummaryResponse(event.getId(), event.getName(), event.getVenue(), event.getStartsAt(),
                event.getSalesOpenAt(), event.getAdmitsPerMinute());
    }
}
