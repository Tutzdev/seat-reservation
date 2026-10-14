package dev.tutz.reservation.event;

import dev.tutz.reservation.common.BusinessRuleException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The seat map as the organizer describes it: named sections, each made of rows with the same
 * number of seats. Building one validates the rules that Bean Validation cannot express on a
 * single field, like unique names across the whole request.
 */
final class SeatLayout {

    private final List<SectionRequest> sections;

    private SeatLayout(List<SectionRequest> sections) {
        this.sections = sections;
    }

    static SeatLayout of(List<SectionRequest> requested, int maxSeats) {
        List<SectionRequest> sections = requested.stream().map(SeatLayout::normalize).toList();

        Set<String> sectionNames = new HashSet<>();
        for (SectionRequest section : sections) {
            if (!sectionNames.add(section.name().toLowerCase(Locale.ROOT))) {
                throw new BusinessRuleException("DUPLICATE_SECTION",
                        "Section '%s' appears more than once.".formatted(section.name()));
            }
            if (new HashSet<>(section.rows()).size() != section.rows().size()) {
                throw new BusinessRuleException("DUPLICATE_ROW",
                        "Section '%s' repeats a row label.".formatted(section.name()));
            }
        }

        SeatLayout layout = new SeatLayout(sections);
        if (layout.totalSeats() > maxSeats) {
            throw new BusinessRuleException("TOO_MANY_SEATS",
                    "An event can have at most %d seats.".formatted(maxSeats));
        }
        return layout;
    }

    int totalSeats() {
        return sections.stream().mapToInt(section -> section.rows().size() * section.seatsPerRow()).sum();
    }

    /** Seats in layout order: section by section, row by row, seat 1 first. */
    List<Seat> seatsFor(UUID eventId) {
        List<Seat> seats = new ArrayList<>(totalSeats());
        for (SectionRequest section : sections) {
            for (String row : section.rows()) {
                for (int number = 1; number <= section.seatsPerRow(); number++) {
                    seats.add(new Seat(eventId, section.name(), row, number, section.priceCents()));
                }
            }
        }
        return seats;
    }

    private static SectionRequest normalize(SectionRequest section) {
        List<String> rows = section.rows().stream().map(row -> row.strip().toUpperCase(Locale.ROOT)).toList();
        return new SectionRequest(section.name().strip(), rows, section.seatsPerRow(), section.priceCents());
    }
}
