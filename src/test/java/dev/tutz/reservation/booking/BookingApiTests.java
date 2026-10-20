package dev.tutz.reservation.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.customer.Customer;
import dev.tutz.reservation.event.EventResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** The HTTP contract: status codes, headers and Problem Details bodies that clients rely on. */
@AutoConfigureMockMvc
class BookingApiTests extends PostgresTestSupport {

    private static final MediaType PROBLEM_JSON = MediaType.APPLICATION_PROBLEM_JSON;

    @Autowired
    private MockMvc mvc;

    private Customer customer;

    @BeforeEach
    void logIn() {
        customer = newCustomer();
    }

    @Test
    void holdAnswers201AndARetryAnswers200WithTheSameBooking() throws Exception {
        EventResponse event = eventWithSeats(2);
        String key = newKey();

        String created = postHold(key, event.id(), seatIdsOf(event))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.customerId").value(customer.getId().toString()))
                .andExpect(jsonPath("$.totalCents").value(20_000))
                .andReturn().getResponse().getContentAsString();
        String bookingId = JsonPath.read(created, "$.id");

        postHold(key, event.id(), seatIdsOf(event))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(bookingId));

        mvc.perform(get("/api/v1/bookings/{id}", bookingId).header(AUTHORIZATION, bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.length()").value(2));
    }

    @Test
    void takenSeatsAnswer409WithTheirIds() throws Exception {
        EventResponse event = eventWithSeats(2);
        List<Long> seats = seatIdsOf(event);
        hold(new HoldRequest(event.id(), List.of(seats.get(1))));

        postHold(newKey(), event.id(), seats)
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SEATS_UNAVAILABLE"))
                .andExpect(jsonPath("$.unavailableSeatIds", contains(seats.get(1).intValue())));
    }

    @Test
    void aSecondHoldForTheSameEventAnswers409WithTheActiveBooking() throws Exception {
        EventResponse event = eventWithSeats(2);
        List<Long> seats = seatIdsOf(event);
        BookingResponse active = hold(customer.getId(), new HoldRequest(event.id(), List.of(seats.get(0))));

        postHold(newKey(), event.id(), List.of(seats.get(1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOLD_ALREADY_ACTIVE"))
                .andExpect(jsonPath("$.bookingId").value(active.id().toString()));
    }

    @Test
    void holdWithoutAnIdempotencyKeyIsRejected() throws Exception {
        EventResponse event = eventWithSeats(1);

        mvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(event.id(), seatIdsOf(event))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_HEADER"));
    }

    @Test
    void holdWithoutATokenAnswers401() throws Exception {
        EventResponse event = eventWithSeats(1);

        mvc.perform(post("/api/v1/bookings").header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdBody(event.id(), seatIdsOf(event))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void invalidBodyListsTheFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, bearer(customer))
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\": []}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("eventId")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("seatIds")));
    }

    @Test
    void malformedIdempotencyKeyIsReportedByItsHeaderName() throws Exception {
        EventResponse event = eventWithSeats(1);

        postHold("short", event.id(), seatIdsOf(event))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
    }

    @Test
    void unknownBookingAnswers404() throws Exception {
        mvc.perform(get("/api/v1/bookings/{id}", UUID.randomUUID()).header(AUTHORIZATION, bearer(customer)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void someoneElsesBookingAnswers404AndStaysUntouched() throws Exception {
        EventResponse event = eventWithSeats(1);
        BookingResponse theirs = hold(new HoldRequest(event.id(), seatIdsOf(event)));

        mvc.perform(get("/api/v1/bookings/{id}", theirs.id()).header(AUTHORIZATION, bearer(customer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(post("/api/v1/bookings/{id}/cancel", theirs.id()).header(AUTHORIZATION, bearer(customer)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/bookings/{id}/payment", theirs.id()).header(AUTHORIZATION, bearer(customer))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerAccountId\": \"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());

        BookingResponse after = bookings.find(theirs.id());
        assertThat(after.status()).isEqualTo(BookingStatus.HELD);
        assertThat(after.payment()).isNull();
    }

    @Test
    void cancelAnswersWithTheCancelledBooking() throws Exception {
        EventResponse event = eventWithSeats(1);
        BookingResponse booking = hold(customer.getId(), new HoldRequest(event.id(), seatIdsOf(event)));

        mvc.perform(post("/api/v1/bookings/{id}/cancel", booking.id()).header(AUTHORIZATION, bearer(customer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancellingAnExpiredHoldAnswers409AndKeepsTheHttpStatusInTheBody() throws Exception {
        EventResponse event = eventWithSeats(1);
        BookingResponse booking = hold(customer.getId(), new HoldRequest(event.id(), seatIdsOf(event)));
        clock.advance(Duration.ofMinutes(11));

        mvc.perform(post("/api/v1/bookings/{id}/cancel", booking.id()).header(AUTHORIZATION, bearer(customer)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_ACTIVE"))
                .andExpect(jsonPath("$.bookingStatus").value("EXPIRED"));
    }

    private ResultActions postHold(String key, UUID eventId, List<Long> seatIds) throws Exception {
        return mvc.perform(post("/api/v1/bookings")
                .header(AUTHORIZATION, bearer(customer))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(holdBody(eventId, seatIds)));
    }

    private static String holdBody(UUID eventId, List<Long> seatIds) {
        String seats = seatIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return """
                {"eventId": "%s", "seatIds": [%s]}
                """.formatted(eventId, seats);
    }
}
