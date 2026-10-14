package dev.tutz.reservation.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI seatReservationApi() {
        return new OpenAPI().info(new Info()
                .title("Seat Reservation API")
                .version("v1")
                .description("""
                        Reserved-seating tickets: hold seats for a few minutes without ever giving the same seat \
                        to two people. Holds require an Idempotency-Key header; errors are RFC 9457 Problem \
                        Details with a stable `code`."""));
    }
}
