package dev.tutz.reservation.common;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ApplicationConfiguration {

    /** Every "now" in the domain comes from here, so tests can move time instead of sleeping. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
