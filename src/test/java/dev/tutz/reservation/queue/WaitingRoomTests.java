package dev.tutz.reservation.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tutz.reservation.Concurrently;
import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.booking.BookingStatus;
import dev.tutz.reservation.booking.HoldRequest;
import dev.tutz.reservation.booking.HoldResult;
import dev.tutz.reservation.common.BusinessRuleException;
import dev.tutz.reservation.common.ResourceNotFoundException;
import dev.tutz.reservation.event.CreateEventRequest;
import dev.tutz.reservation.event.EventResponse;
import dev.tutz.reservation.event.SectionRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The waiting room lets buyers in one at a time, at the pace the organizer chose, and only they hold seats. */
class WaitingRoomTests extends PostgresTestSupport {

    private static final int ONE_PER_SECOND = 60;
    private static final Duration PASS = Duration.ofMinutes(10);

    @Autowired
    private QueueService queue;

    private Instant now;

    @BeforeEach
    void stopTheClock() {
        // Stopped on a whole millisecond, the precision Redis keeps, so admission times can be compared exactly.
        clock.advance(Duration.ZERO);
        Instant stopped = clock.instant();
        clock.advance(Duration.between(stopped, stopped.truncatedTo(ChronoUnit.MILLIS)));
        now = clock.instant();
    }

    @Test
    void buyersAreLetInOneIntervalApartInTheOrderTheyJoined() {
        EventResponse event = eventWithWaitingRoom(10, ONE_PER_SECOND);

        List<QueueResponse> places = IntStream.range(0, 3)
                .mapToObj(i -> queue.join(event.id(), newCustomer().getId()))
                .toList();

        assertThat(places).extracting(QueueResponse::admittedAt)
                .containsExactly(now, now.plusSeconds(1), now.plusSeconds(2));
        assertThat(places).extracting(QueueResponse::position).containsExactly(0L, 1L, 2L);
        assertThat(places).extracting(QueueResponse::admitted).containsExactly(true, false, false);
        assertThat(places.getFirst().passExpiresAt()).isEqualTo(now.plus(PASS));
    }

    @Test
    void joiningAgainKeepsThePlace() {
        EventResponse event = eventWithWaitingRoom(10, ONE_PER_SECOND);
        queue.join(event.id(), newCustomer().getId());
        UUID customer = newCustomer().getId();

        QueueResponse first = queue.join(event.id(), customer);
        QueueResponse again = queue.join(event.id(), customer);

        assertThat(again).isEqualTo(first);
        assertThat(queue.status(event.id(), customer)).isEqualTo(first);
    }

    @Test
    void aBuyerIsLetInWhenTheirTimeComes() {
        EventResponse event = eventWithWaitingRoom(10, ONE_PER_SECOND);
        queue.join(event.id(), newCustomer().getId());
        UUID customer = newCustomer().getId();
        queue.join(event.id(), customer);

        clock.advance(Duration.ofSeconds(1));

        QueueResponse status = queue.status(event.id(), customer);
        assertThat(status.admitted()).isTrue();
        assertThat(status.position()).isZero();
    }

    @Test
    void anExpiredPassSendsTheBuyerToTheBack() {
        EventResponse event = eventWithWaitingRoom(10, ONE_PER_SECOND);
        UUID latecomer = newCustomer().getId();
        queue.join(event.id(), latecomer);
        clock.advance(PASS);

        assertThatThrownBy(() -> queue.status(event.id(), latecomer)).isInstanceOf(ResourceNotFoundException.class);
        Instant later = clock.instant();
        queue.join(event.id(), newCustomer().getId());
        assertThat(queue.join(event.id(), latecomer).admittedAt()).isEqualTo(later.plusSeconds(1));
    }

    @Test
    void aCrowdJoiningAtOnceGetsOnePlaceEach() throws Exception {
        EventResponse event = eventWithWaitingRoom(10, 600);
        List<UUID> crowd = IntStream.range(0, 50).mapToObj(i -> newCustomer().getId()).toList();

        List<Instant> admissions = Concurrently.run(crowd.stream()
                .<Callable<Instant>>map(customer -> () -> queue.join(event.id(), customer).admittedAt())
                .toList());

        assertThat(admissions).containsExactlyInAnyOrderElementsOf(IntStream.range(0, 50)
                .mapToObj(i -> now.plusMillis(100L * i))
                .toList());
    }

    @Test
    void onlyBuyersWhoWereLetInHoldSeats() {
        EventResponse event = eventWithWaitingRoom(10, ONE_PER_SECOND);
        List<Long> seats = seatIdsOf(event);
        queue.join(event.id(), newCustomer().getId());
        UUID customer = newCustomer().getId();
        HoldRequest request = new HoldRequest(event.id(), List.of(seats.getFirst()));

        assertThatThrownBy(() -> hold(customer, request))
                .isInstanceOfSatisfying(NotAdmittedException.class,
                        notInRoom -> assertThat(notInRoom.getRetryAfter()).isNull());

        queue.join(event.id(), customer);
        assertThatThrownBy(() -> hold(customer, request))
                .isInstanceOfSatisfying(NotAdmittedException.class,
                        waiting -> assertThat(waiting.getRetryAfter()).isEqualTo(Duration.ofSeconds(1)));

        clock.advance(Duration.ofSeconds(1));
        assertThat(hold(customer, request).status()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void aRetriedHoldIsAnsweredAfterThePassExpired() {
        EventResponse event = eventWithWaitingRoom(10, ONE_PER_SECOND);
        UUID customer = newCustomer().getId();
        queue.join(event.id(), customer);
        HoldRequest request = new HoldRequest(event.id(), List.of(seatIdsOf(event).getFirst()));
        String key = newKey();
        HoldResult first = bookings.hold(customer, request, key);
        clock.advance(PASS);

        HoldResult retry = bookings.hold(customer, request, key);

        assertThat(retry.replayed()).isTrue();
        assertThat(retry.booking().id()).isEqualTo(first.booking().id());
    }

    @Test
    void eventsWithoutAWaitingRoomHoldRightAway() {
        EventResponse event = eventWithSeats(1);

        assertThatThrownBy(() -> queue.join(event.id(), newCustomer().getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("NO_WAITING_ROOM");
        assertThat(hold(new HoldRequest(event.id(), seatIdsOf(event))).status()).isEqualTo(BookingStatus.HELD);
    }

    @Test
    void theRoomOpensWithTheSale() {
        EventResponse event = events.create(new CreateEventRequest("Presale " + UUID.randomUUID(), "Arena",
                clock.instant().plus(Duration.ofDays(30)), List.of(new SectionRequest("Floor", List.of("A"), 1, 0)),
                clock.instant().plus(Duration.ofHours(1)), ONE_PER_SECOND));
        UUID customer = newCustomer().getId();

        assertThatThrownBy(() -> queue.join(event.id(), customer))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("SALE_NOT_OPEN");

        clock.advance(Duration.ofHours(1));
        assertThat(queue.join(event.id(), customer).admitted()).isTrue();
    }
}
