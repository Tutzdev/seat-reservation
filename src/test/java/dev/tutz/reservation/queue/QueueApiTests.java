package dev.tutz.reservation.queue;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.event.EventResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** The waiting room over HTTP: joining, polling, and the 403 a hold gets before its turn. */
@AutoConfigureMockMvc
class QueueApiTests extends PostgresTestSupport {

    @Autowired
    private MockMvc mvc;

    @Test
    void aBuyerJoinsAndPollsTheirPlace() throws Exception {
        clock.advance(Duration.ZERO);
        EventResponse event = eventWithWaitingRoom(5, 60);
        String token = bearer(newCustomer());

        mvc.perform(post("/api/v1/events/{id}/queue", event.id()).header(AUTHORIZATION, bearer(newCustomer())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admitted").value(true));
        mvc.perform(post("/api/v1/events/{id}/queue", event.id()).header(AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value(event.id().toString()))
                .andExpect(jsonPath("$.admitted").value(false))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.admittedAt").exists())
                .andExpect(jsonPath("$.passExpiresAt").exists());

        mvc.perform(get("/api/v1/events/{id}/queue", event.id()).header(AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(1));
    }

    @Test
    void aHoldBeforeItsTurnAnswers403WithRetryAfter() throws Exception {
        clock.advance(Duration.ZERO);
        EventResponse event = eventWithWaitingRoom(5, 60);
        queue(event, bearer(newCustomer()));
        String token = bearer(newCustomer());
        queue(event, token);

        mvc.perform(post("/api/v1/bookings").header(AUTHORIZATION, token).header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId": "%s", "seatIds": [%d]}
                                """.formatted(event.id(), seatIdsOf(event).getFirst())))
                .andExpect(status().isForbidden())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_ADMITTED"));
    }

    @Test
    void aBuyerWhoNeverJoinedIsNotInTheRoom() throws Exception {
        EventResponse event = eventWithWaitingRoom(5, 60);

        mvc.perform(get("/api/v1/events/{id}/queue", event.id()).header(AUTHORIZATION, bearer(newCustomer())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void theRoomNeedsALoginWhileTheEventPageDoesNot() throws Exception {
        EventResponse event = eventWithWaitingRoom(5, 60);

        mvc.perform(post("/api/v1/events/{id}/queue", event.id()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(get("/api/v1/events/{id}", event.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admitsPerMinute").value(60));
    }

    private void queue(EventResponse event, String token) throws Exception {
        mvc.perform(post("/api/v1/events/{id}/queue", event.id()).header(AUTHORIZATION, token))
                .andExpect(status().isOk());
    }
}
