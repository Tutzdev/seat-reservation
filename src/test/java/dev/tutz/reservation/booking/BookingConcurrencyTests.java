package dev.tutz.reservation.booking;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tutz.reservation.Concurrently;
import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.event.EventResponse;
import dev.tutz.reservation.event.Seat;
import dev.tutz.reservation.event.SeatRepository;
import dev.tutz.reservation.event.SeatStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BookingConcurrencyTests extends PostgresTestSupport {

    @Autowired
    private SeatRepository seatRepository;

    @Test
    void oneSeatAndAHundredBuyersMakeExactlyOneHold() throws Exception {
        EventResponse event = eventWithSeats(1);
        Long seat = seatIdsOf(event).getFirst();

        List<Optional<BookingResponse>> outcomes = Concurrently.run(100,
                () -> tryHold(new HoldRequest(event.id(), List.of(seat))));

        List<BookingResponse> winners = outcomes.stream().flatMap(Optional::stream).toList();
        assertThat(winners).hasSize(1);
        Seat stored = seatRepository.findById(seat).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(SeatStatus.HELD);
        assertThat(stored.getBookingId()).isEqualTo(winners.getFirst().id());
    }

    /**
     * Requests for overlapping seats, sent in random order, all at once. A deadlock would surface
     * as an unexpected exception; a double sale as a seat that two successful bookings claim.
     */
    @Test
    void overlappingHoldsNeverShareASeatAndNeverDeadlock() throws Exception {
        EventResponse event = eventWithSeats(6);
        List<Long> seats = seatIdsOf(event);

        List<Callable<Optional<BookingResponse>>> tasks = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            List<Long> shuffled = new ArrayList<>(seats);
            Collections.shuffle(shuffled, ThreadLocalRandom.current());
            HoldRequest request = new HoldRequest(event.id(), shuffled.subList(0, 3));
            tasks.add(() -> tryHold(request));
        }
        List<BookingResponse> winners = Concurrently.run(tasks).stream().flatMap(Optional::stream).toList();

        assertThat(winners).isNotEmpty();
        Map<Long, UUID> claimedBy = new HashMap<>();
        for (BookingResponse winner : winners) {
            for (BookingResponse.SeatLine line : winner.seats()) {
                assertThat(claimedBy.put(line.seatId(), winner.id()))
                        .as("seat %d claimed by two bookings", line.seatId())
                        .isNull();
            }
        }
        for (Long seatId : claimedBy.keySet()) {
            assertThat(seatRepository.findById(seatId).orElseThrow().getBookingId()).isEqualTo(claimedBy.get(seatId));
        }
    }

    private Optional<BookingResponse> tryHold(HoldRequest request) {
        try {
            return Optional.of(hold(request));
        } catch (ConflictException conflict) {
            assertThat(conflict.getCode()).isEqualTo("SEATS_UNAVAILABLE");
            return Optional.empty();
        }
    }
}
