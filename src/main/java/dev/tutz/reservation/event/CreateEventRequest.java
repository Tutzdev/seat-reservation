package dev.tutz.reservation.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record CreateEventRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 120) String venue,
        @NotNull Instant startsAt,
        @NotEmpty @Size(max = 20) List<@Valid @NotNull SectionRequest> sections) {
}
