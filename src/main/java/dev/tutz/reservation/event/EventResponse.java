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
        long availableSeats,
        List<SectionResponse> sections) {

    public EventResponse {
        sections = List.copyOf(sections);
    }

    static EventResponse from(Event event, List<SectionResponse> sections) {
        long totalSeats = sections.stream().mapToLong(SectionResponse::seats).sum();
        long availableSeats = sections.stream().mapToLong(SectionResponse::available).sum();
        return new EventResponse(event.getId(), event.getName(), event.getVenue(), event.getStartsAt(),
                totalSeats, availableSeats, sections);
    }
}
