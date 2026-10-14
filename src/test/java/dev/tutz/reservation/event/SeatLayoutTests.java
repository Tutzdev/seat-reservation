package dev.tutz.reservation.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tutz.reservation.common.BusinessRuleException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SeatLayoutTests {

    private static final UUID EVENT_ID = UUID.randomUUID();

    @Test
    void generatesEverySeatInLayoutOrder() {
        SeatLayout layout = SeatLayout.of(List.of(
                new SectionRequest("Floor", List.of("A", "B"), 3, 30_000),
                new SectionRequest("Balcony", List.of("C"), 2, 15_000)), 100);

        List<Seat> seats = layout.seatsFor(EVENT_ID);

        assertThat(layout.totalSeats()).isEqualTo(8);
        assertThat(seats).extracting(seat -> seat.getSection() + "-" + seat.getRowLabel() + seat.getNumber())
                .containsExactly("Floor-A1", "Floor-A2", "Floor-A3", "Floor-B1", "Floor-B2", "Floor-B3",
                        "Balcony-C1", "Balcony-C2");
        assertThat(seats).filteredOn(seat -> seat.getSection().equals("Balcony"))
                .extracting(Seat::getPriceCents).containsOnly(15_000L);
    }

    @Test
    void normalizesNamesAndRowLabels() {
        SeatLayout layout = SeatLayout.of(List.of(new SectionRequest("  Floor ", List.of(" a "), 1, 0)), 10);

        Seat seat = layout.seatsFor(EVENT_ID).getFirst();

        assertThat(seat.getSection()).isEqualTo("Floor");
        assertThat(seat.getRowLabel()).isEqualTo("A");
    }

    @Test
    void rejectsTheSameSectionTwiceIgnoringCase() {
        List<SectionRequest> sections = List.of(
                new SectionRequest("Floor", List.of("A"), 1, 0),
                new SectionRequest("FLOOR", List.of("B"), 1, 0));

        assertThatThrownBy(() -> SeatLayout.of(sections, 10))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("DUPLICATE_SECTION");
    }

    @Test
    void rejectsARepeatedRowInsideASection() {
        List<SectionRequest> sections = List.of(new SectionRequest("Floor", List.of("A", "a"), 1, 0));

        assertThatThrownBy(() -> SeatLayout.of(sections, 10))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("DUPLICATE_ROW");
    }

    @Test
    void rejectsALayoutBiggerThanTheLimit() {
        List<SectionRequest> sections = List.of(new SectionRequest("Floor", List.of("A", "B"), 6, 0));

        assertThatThrownBy(() -> SeatLayout.of(sections, 10))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("TOO_MANY_SEATS");
    }
}
