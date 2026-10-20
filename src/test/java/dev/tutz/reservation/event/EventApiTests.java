package dev.tutz.reservation.event;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.customer.Role;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class EventApiTests extends PostgresTestSupport {

    @Autowired
    private MockMvc mvc;

    @Test
    void createsAnEventAndServesItsSeatMap() throws Exception {
        String body = """
                {
                  "name": "Jazz Night",
                  "venue": "Blue Note",
                  "startsAt": "%s",
                  "sections": [
                    {"name": "Front", "rows": ["A", "B"], "seatsPerRow": 3, "priceCents": 12000}
                  ]
                }
                """.formatted(clock.instant().plus(Duration.ofDays(3)));

        String location = mvc.perform(createEvent(body).header(AUTHORIZATION, bearer(newCustomer(Role.ORGANIZER))))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.totalSeats").value(6))
                .andExpect(jsonPath("$.availableSeats").value(6))
                .andExpect(jsonPath("$.sections[0].priceCents").value(12_000))
                .andReturn().getResponse().getHeader("Location");

        mvc.perform(get(location + "/seats").param("section", "Front"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].row").value("A"))
                .andExpect(jsonPath("$[0].number").value(1))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"));
    }

    @Test
    void invalidSectionIsRejectedWithFieldErrors() throws Exception {
        String body = """
                {
                  "name": "Broken",
                  "venue": "Nowhere",
                  "startsAt": "%s",
                  "sections": [{"name": "Front", "rows": ["A"], "seatsPerRow": 0, "priceCents": -1}]
                }
                """.formatted(clock.instant().plus(Duration.ofDays(3)));

        mvc.perform(createEvent(body).header(AUTHORIZATION, bearer(newCustomer(Role.ORGANIZER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("sections[0].seatsPerRow")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("sections[0].priceCents")));
    }

    @Test
    void layoutRuleViolationAnswers422() throws Exception {
        String body = """
                {
                  "name": "Twice",
                  "venue": "Somewhere",
                  "startsAt": "%s",
                  "sections": [
                    {"name": "Floor", "rows": ["A"], "seatsPerRow": 1, "priceCents": 0},
                    {"name": "floor", "rows": ["B"], "seatsPerRow": 1, "priceCents": 0}
                  ]
                }
                """.formatted(clock.instant().plus(Duration.ofDays(3)));

        mvc.perform(createEvent(body).header(AUTHORIZATION, bearer(newCustomer(Role.ORGANIZER))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SECTION"));
    }

    @Test
    void publishesTheOpenApiDocument() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Seat Reservation API"))
                .andExpect(jsonPath("$.paths['/api/v1/bookings'].post").exists());
    }

    @Test
    void unknownEventAnswers404() throws Exception {
        mvc.perform(get("/api/v1/events/{id}/seats", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void onlyOrganizersCreateEventsButAnyoneBrowsesThem() throws Exception {
        String body = """
                {"name": "Closed", "venue": "Hall", "startsAt": "%s",
                 "sections": [{"name": "Floor", "rows": ["A"], "seatsPerRow": 1, "priceCents": 0}]}
                """.formatted(clock.instant().plus(Duration.ofDays(3)));

        mvc.perform(createEvent(body))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(createEvent(body).header(AUTHORIZATION, bearer(newCustomer())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        EventResponse event = eventWithSeats(1);
        mvc.perform(get("/api/v1/events")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/events/{id}", event.id())).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder createEvent(String body) {
        return post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
