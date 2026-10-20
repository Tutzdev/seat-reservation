package dev.tutz.reservation;

import dev.tutz.reservation.booking.BookingResponse;
import dev.tutz.reservation.booking.BookingService;
import dev.tutz.reservation.booking.HoldRequest;
import dev.tutz.reservation.customer.AccessTokens;
import dev.tutz.reservation.customer.Customer;
import dev.tutz.reservation.customer.CustomerRepository;
import dev.tutz.reservation.customer.Role;
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
@SpringBootTest(properties = {
        "reservation.bookings.sweep.interval=PT1H", // tests call the sweeper and the reconciler themselves
        "reservation.payments.reconcile.interval=PT1H",
        "reservation.payments.ledger.email=tickets@ledger.test",
        "reservation.payments.ledger.password=not-used-by-tests",
        "reservation.payments.ledger.merchant-account-id=00000000-0000-0000-0000-00000000c0de",
        "reservation.security.jwt-secret=test-secret-with-at-least-32-characters"})
@Import({TestClockConfiguration.class, TestPaymentConfiguration.class})
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

    @Autowired
    protected BookingService bookings;

    @Autowired
    protected FakePaymentGateway ledger;

    @Autowired
    protected CustomerRepository customers;

    @Autowired
    private AccessTokens tokens;

    @BeforeEach
    void resetFakes() {
        clock.reset();
        ledger.reset();
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

    /** A first-time hold with a fresh Idempotency-Key. */
    protected BookingResponse hold(HoldRequest request) {
        return bookings.hold(request, newKey()).booking();
    }

    /** Saved directly with a placeholder hash: hashing a real password per test would only slow the suite down. */
    protected Customer newCustomer(Role role) {
        return customers.save(Customer.register(UUID.randomUUID() + "@test.dev", "Test customer", "not-a-hash", role,
                clock.instant()));
    }

    protected Customer newCustomer() {
        return newCustomer(Role.CUSTOMER);
    }

    /** An Authorization header value for this customer. */
    protected String bearer(Customer customer) {
        return "Bearer " + tokens.issue(customer).accessToken();
    }

    protected static String newKey() {
        return UUID.randomUUID().toString();
    }
}
