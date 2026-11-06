package dev.tutz.reservation.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tutz.reservation.PostgresTestSupport;
import dev.tutz.reservation.event.EventResponse;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 120 requests per minute per customer: a burst of 120, then one every half second. */
@AutoConfigureMockMvc
class CustomerRateLimitTests extends PostgresTestSupport {

    private static final int LIMIT = 120;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void stopTheClock() {
        clock.advance(Duration.ZERO);
    }

    @Test
    void aCustomerOverTheLimitWaitsForTheNextRequest() throws Exception {
        String token = bearer(newCustomer());
        for (int i = 0; i < LIMIT; i++) {
            me(token).andExpect(status().isOk());
        }
        double rejected = meters.counter("reservation.rate_limit.rejected", "limit", "customer").count();

        me(token)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));

        assertThat(meters.counter("reservation.rate_limit.rejected", "limit", "customer").count())
                .isEqualTo(rejected + 1);

        clock.advance(Duration.ofMillis(500));
        me(token).andExpect(status().isOk());
        me(token).andExpect(status().isTooManyRequests());
    }

    @Test
    void eachCustomerHasTheirOwnAllowance() throws Exception {
        String busy = bearer(newCustomer());
        for (int i = 0; i < LIMIT; i++) {
            me(busy).andExpect(status().isOk());
        }
        me(busy).andExpect(status().isTooManyRequests());

        me(bearer(newCustomer())).andExpect(status().isOk());
    }

    @Test
    void anonymousRequestsAreNotCountedHere() throws Exception {
        EventResponse event = eventWithSeats(1);

        for (int i = 0; i <= LIMIT; i++) {
            mvc.perform(get("/api/v1/events/{id}", event.id())).andExpect(status().isOk());
        }
    }

    private ResultActions me(String token) throws Exception {
        return mvc.perform(get("/api/v1/customers/me").header(AUTHORIZATION, token));
    }
}
