package dev.tutz.reservation.event;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record EventResponse(
        UUID id,
        String name,
        String venue,
        Instant startsAt,
        long totalSeats,
        List<SectionResponse> sections) {

    public EventResponse {
        sections = List.copyOf(sections);
    }

    static EventResponse from(Event event, List<SectionResponse> sections) {
        long totalSeats = sections.stream().mapToLong(SectionResponse::seats).sum();
        return new EventResponse(event.getId(), event.getName(), event.getVenue(), event.getStartsAt(),
                totalSeats, sections);
    }
}
