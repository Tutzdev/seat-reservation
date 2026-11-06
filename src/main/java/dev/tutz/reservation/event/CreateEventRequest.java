package dev.tutz.reservation.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * @param salesOpenAt when seats go on sale; right away when left out
 * @param admitsPerMinute puts the sale behind a waiting room that lets this many buyers in per minute
 */
public record CreateEventRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 120) String venue,
        @NotNull Instant startsAt,
        @NotEmpty @Size(max = 20) List<@Valid @NotNull SectionRequest> sections,
        Instant salesOpenAt,
        @Min(1) @Max(60_000) Integer admitsPerMinute) {

    /** An event on sale as soon as it is created, without a waiting room. */
    public CreateEventRequest(String name, String venue, Instant startsAt, List<SectionRequest> sections) {
        this(name, venue, startsAt, sections, null, null);
    }
}
