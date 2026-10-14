package dev.tutz.reservation.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tutz.reservation.Concurrently;
import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.common.BusinessRuleException;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.event.EventResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class IdempotentHoldTests extends PostgresTestSupport {

    @Test
    void retryWithTheSameKeyReturnsTheOriginalBooking() {
        EventResponse event = eventWithSeats(2);
        List<Long> seats = seatIdsOf(event);
        String key = newKey();

        HoldResult first = bookings.hold(new HoldRequest(event.id(), seats), key);
        HoldResult retry = bookings.hold(new HoldRequest(event.id(), seats.reversed()), key);

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.booking()).isEqualTo(first.booking());
    }

    @Test
    void sameKeyForDifferentSeatsIsRejected() {
        EventResponse event = eventWithSeats(2);
        List<Long> seats = seatIdsOf(event);
        String key = newKey();
        bookings.hold(new HoldRequest(event.id(), List.of(seats.get(0))), key);

        assertThatThrownBy(() -> bookings.hold(new HoldRequest(event.id(), List.of(seats.get(1))), key))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void concurrentRetriesWithTheSameKeyCreateOneBooking() throws Exception {
        EventResponse event = eventWithSeats(1);
        HoldRequest request = new HoldRequest(event.id(), seatIdsOf(event));
        String key = newKey();

        List<HoldResult> results = Concurrently.run(20, () -> bookings.hold(request, key));

        assertThat(results).extracting(result -> result.booking().id()).containsOnly(results.getFirst().booking().id());
        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
    }

    @Test
    void aHoldThatLostItsSeatsIsNotRememberedAndCanBeRetried() {
        EventResponse event = eventWithSeats(1);
        List<Long> seats = seatIdsOf(event);
        BookingResponse someoneElse = hold(new HoldRequest(event.id(), seats));
        String key = newKey();

        assertThatThrownBy(() -> bookings.hold(new HoldRequest(event.id(), seats), key))
                .isInstanceOf(ConflictException.class);
        bookings.cancel(someoneElse.id());

        HoldResult retry = bookings.hold(new HoldRequest(event.id(), seats), key);
        assertThat(retry.replayed()).isFalse();
        assertThat(retry.booking().status()).isEqualTo(BookingStatus.HELD);
    }
}
