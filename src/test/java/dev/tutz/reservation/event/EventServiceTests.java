package dev.tutz.reservation.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.common.BusinessRuleException;
import dev.tutz.reservation.common.ResourceNotFoundException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventServiceTests extends PostgresTestSupport {

    @Test
    void createsTheEventWithItsWholeSeatMap() {
        EventResponse event = events.create(new CreateEventRequest("Rock in Rio", "Parque Olímpico",
                Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS), List.of(
                        new SectionRequest("Floor", List.of("A", "B", "C"), 10, 45_000),
                        new SectionRequest("Upper", List.of("D"), 5, 20_000))));

        assertThat(event.totalSeats()).isEqualTo(35);
        assertThat(event.sections()).containsExactly(
                new SectionResponse("Floor", 30, 30, 45_000),
                new SectionResponse("Upper", 5, 5, 20_000));
        assertThat(events.find(event.id())).isEqualTo(event);
    }

    @Test
    void seatMapFollowsTheLayoutAndCanBeFilteredBySection() {
        EventResponse event = events.create(new CreateEventRequest("Theater", "Municipal",
                Instant.now().plus(1, ChronoUnit.DAYS), List.of(
                        new SectionRequest("Orchestra", List.of("A"), 2, 10_000),
                        new SectionRequest("Box", List.of("B"), 1, 25_000))));

        assertThat(events.seatMap(event.id(), null))
                .extracting(seat -> seat.section() + "-" + seat.row() + seat.number())
                .containsExactly("Orchestra-A1", "Orchestra-A2", "Box-B1");
        assertThat(events.seatMap(event.id(), "Box")).singleElement()
                .extracting(SeatResponse::priceCents).isEqualTo(25_000L);
    }

    @Test
    void rejectsAnEventThatAlreadyStarted() {
        CreateEventRequest request = new CreateEventRequest("Yesterday", "Somewhere",
                Instant.now().minus(1, ChronoUnit.DAYS), List.of(new SectionRequest("Floor", List.of("A"), 1, 0)));

        assertThatThrownBy(() -> events.create(request))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("EVENT_IN_THE_PAST");
    }

    @Test
    void unknownEventIsNotFound() {
        assertThatThrownBy(() -> events.seatMap(UUID.randomUUID(), null))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
