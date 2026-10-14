package dev.tutz.reservation.event;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

/** A block of rows that share a name and a price, like "Floor" or "Upper Level 2". */
public record SectionRequest(
        @NotBlank @Size(max = 40) String name,
        @NotEmpty @Size(max = 50) List<@NotBlank @Pattern(regexp = "[A-Za-z0-9]{1,8}") String> rows,
        @Min(1) @Max(100) int seatsPerRow,
        @PositiveOrZero long priceCents) {
}
