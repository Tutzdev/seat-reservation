package dev.tutz.reservation.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    private static final String BEARER = "bearer";

    /** The Authorize button of Swagger UI takes the token from POST /api/v1/auth/token. */
    @Bean
    OpenAPI seatReservationApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Seat Reservation API")
                        .version("v1")
                        .description("""
                                Reserved-seating tickets: hold seats for a few minutes without ever giving the same \
                                seat to two people. Log in with POST /api/v1/auth/token and send the token as a \
                                Bearer header. Holds require an Idempotency-Key header; errors are RFC 9457 Problem \
                                Details with a stable `code`."""))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
