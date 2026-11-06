package dev.tutz.reservation.customer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tutz.reservation.PostgresTestSupport;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Ten failed logins per e-mail, then one every 90 seconds: guessing a password by trying fast does not work. */
@AutoConfigureMockMvc
class LoginRateLimitTests extends PostgresTestSupport {

    private static final String PASSWORD = "correct horse battery";

    @Autowired
    private MockMvc mvc;

    private String email;

    @BeforeEach
    void register() throws Exception {
        clock.advance(Duration.ZERO);
        email = "ana-" + UUID.randomUUID() + "@mail.test";
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email": "%s", "password": "%s", "name": "Ana Souza"}
                """.formatted(email, PASSWORD))).andExpect(status().isCreated());
    }

    @Test
    void afterTenFailuresEvenTheRightPasswordWaits() throws Exception {
        failTenTimes(email);

        login(email, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "90"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_LOGIN_ATTEMPTS"));

        clock.advance(Duration.ofSeconds(90));
        login(email, PASSWORD).andExpect(status().isOk());
    }

    @Test
    void successfulLoginsDoNotCount() throws Exception {
        for (int i = 0; i < 15; i++) {
            login(email, PASSWORD).andExpect(status().isOk());
        }
        login(email, "not the password").andExpect(status().isUnauthorized());
    }

    @Test
    void theLimitIsPerEmailWhateverItsCase() throws Exception {
        failTenTimes(email.toUpperCase());

        login(email, PASSWORD).andExpect(status().isTooManyRequests());
        login("someone-" + UUID.randomUUID() + "@mail.test", PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownEmailsAreLimitedTheSameWay() throws Exception {
        String unknown = "nobody-" + UUID.randomUUID() + "@mail.test";
        failTenTimes(unknown);

        login(unknown, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_LOGIN_ATTEMPTS"));
    }

    private void failTenTimes(String address) throws Exception {
        for (int i = 0; i < 10; i++) {
            login(address, "guess number " + i).andExpect(status().isUnauthorized());
        }
    }

    private ResultActions login(String address, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/token").contentType(MediaType.APPLICATION_JSON).content("""
                {"email": "%s", "password": "%s"}
                """.formatted(address, password)));
    }
}
