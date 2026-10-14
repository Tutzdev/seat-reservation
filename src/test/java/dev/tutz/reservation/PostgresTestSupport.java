package dev.tutz.reservation;

import dev.tutz.reservation.event.CreateEventRequest;
import dev.tutz.reservation.event.EventResponse;
import dev.tutz.reservation.event.EventService;
import dev.tutz.reservation.event.SeatResponse;
import dev.tutz.reservation.event.SectionRequest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs against a real PostgreSQL: row locks and conditional updates under concurrency
 * cannot be faked by an in-memory database. One container is shared by every test class.
 */
@SpringBootTest(properties = "reservation.bookings.sweep.interval=PT1H") // tests call the sweeper themselves
@Import(TestClockConfiguration.class)
public abstract class PostgresTestSupport {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected EventService events;

    @BeforeEach
    void resetClock() {
        clock.reset();
    }

    /** An event a week from now with one section of {@code seats} seats in row A, 100.00 each. */
    protected EventResponse eventWithSeats(int seats) {
        return events.create(new CreateEventRequest("Test event " + UUID.randomUUID(), "Test venue",
                clock.instant().plus(Duration.ofDays(7)),
                List.of(new SectionRequest("Floor", List.of("A"), seats, 10_000))));
    }

    protected List<Long> seatIdsOf(EventResponse event) {
        return events.seatMap(event.id(), null).stream().map(SeatResponse::id).toList();
    }
}
