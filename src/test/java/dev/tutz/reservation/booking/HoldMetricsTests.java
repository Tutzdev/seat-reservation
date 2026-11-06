package dev.tutz.reservation.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.common.BusinessRuleException;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.event.EventResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Every hold attempt is timed by outcome, so a load test can tell won holds from seats lost to someone faster. */
class HoldMetricsTests extends PostgresTestSupport {

    @Autowired
    private MeterRegistry meters;

    @Test
    void wonLostReplayedAndRejectedHoldsAreCountedApart() {
        EventResponse event = eventWithSeats(7);
        List<Long> seats = seatIdsOf(event);
        double won = count("won", "none");
        double lost = count("lost", "SEATS_UNAVAILABLE");
        double replayed = count("replayed", "none");
        double rejected = count("rejected", "TOO_MANY_SEATS");

        UUID customer = newCustomer().getId();
        String key = newKey();
        HoldRequest first = new HoldRequest(event.id(), List.of(seats.get(0)));
        bookings.hold(customer, first, key);
        bookings.hold(customer, first, key);
        assertThatThrownBy(() -> hold(new HoldRequest(event.id(), List.of(seats.get(0)))))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> hold(new HoldRequest(event.id(), seats)))
                .isInstanceOf(BusinessRuleException.class);

        assertThat(count("won", "none")).isEqualTo(won + 1);
        assertThat(count("replayed", "none")).isEqualTo(replayed + 1);
        assertThat(count("lost", "SEATS_UNAVAILABLE")).isEqualTo(lost + 1);
        assertThat(count("rejected", "TOO_MANY_SEATS")).isEqualTo(rejected + 1);
    }

    private double count(String outcome, String code) {
        Timer timer = meters.find(HoldObservation.NAME).tag("outcome", outcome).tag("code", code).timer();
        return timer == null ? 0 : timer.count();
    }
}
