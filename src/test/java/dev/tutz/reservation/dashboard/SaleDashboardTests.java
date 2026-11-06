package dev.tutz.reservation.dashboard;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.booking.HoldRequest;
import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.customer.Role;
import dev.tutz.reservation.event.EventResponse;
import dev.tutz.reservation.queue.QueueService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The organizer's live view of a sale: seats, holds won and lost, and the waiting line. */
@AutoConfigureMockMvc
class SaleDashboardTests extends PostgresTestSupport {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private QueueService queue;

    @Test
    void anOrganizerSeesSeatsHoldsAndTheLine() throws Exception {
        clock.advance(Duration.ZERO);
        EventResponse event = eventWithWaitingRoom(4, 60);
        List<Long> seats = seatIdsOf(event);
        var first = newCustomer().getId();
        queue.join(event.id(), first);
        hold(first, new HoldRequest(event.id(), List.of(seats.get(0), seats.get(1))));
        clock.advance(Duration.ofSeconds(1));
        var second = newCustomer().getId();
        queue.join(event.id(), second);
        assertThatThrownBy(() -> hold(second, new HoldRequest(event.id(), List.of(seats.get(1)))))
                .isInstanceOf(ConflictException.class);
        queue.join(event.id(), newCustomer().getId());
        queue.join(event.id(), newCustomer().getId());

        mvc.perform(get("/api/v1/events/{id}/dashboard", event.id())
                        .header(AUTHORIZATION, bearer(newCustomer(Role.ORGANIZER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.total").value(4))
                .andExpect(jsonPath("$.seats.available").value(2))
                .andExpect(jsonPath("$.seats.held").value(2))
                .andExpect(jsonPath("$.seats.sold").value(0))
                .andExpect(jsonPath("$.holds.won").value(1))
                .andExpect(jsonPath("$.holds.lost").value(1))
                .andExpect(jsonPath("$.waitingRoom.admitsPerMinute").value(60))
                .andExpect(jsonPath("$.waitingRoom.waiting").value(2))
                .andExpect(jsonPath("$.waitingRoom.clearsAt").exists());
    }

    @Test
    void customersCannotSeeIt() throws Exception {
        EventResponse event = eventWithSeats(1);

        mvc.perform(get("/api/v1/events/{id}/dashboard", event.id()).header(AUTHORIZATION, bearer(newCustomer())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/events/{id}/dashboard", event.id()))
                .andExpect(status().isUnauthorized());
    }
}
