package dev.tutz.reservation.customer;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import dev.tutz.reservation.ratelimit.CustomerRateLimitFilter;
import dev.tutz.reservation.ratelimit.RateLimitProperties;
import dev.tutz.reservation.ratelimit.RateLimiter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration
public class SecurityConfiguration {

    /**
     * Failed authentication and authorization are handed to the MVC exception handlers, so a 401 or 403 is the
     * same Problem Details body as every other error instead of Spring Security's empty response.
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors, RateLimiter rateLimiter,
            RateLimitProperties rateLimits, Clock clock) throws Exception {
        return http
                // Bearer tokens travel in a header, never in a cookie, so there is no CSRF surface.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/token").permitAll()
                        // A place in the waiting room belongs to a customer, unlike the rest of the event pages.
                        .requestMatchers("/api/v1/events/*/queue").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/events/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/events").hasRole(Role.ORGANIZER.name())
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesFromToken()))
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                            errors.resolveException(request, response, null, exception);
                        })
                        .accessDeniedHandler((request, response, exception) ->
                                errors.resolveException(request, response, null, exception)))
                // After the token is read, so the customer is known; before authorization and the controllers.
                .addFilterAfter(new CustomerRateLimitFilter(rateLimiter, rateLimits, clock, errors),
                        BearerTokenAuthenticationFilter.class)
                .build();
    }

    /** Expiry is checked against the application clock, like every other deadline in this service. */
    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator notExpired = new JwtTimestampValidator();
        notExpired.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(notExpired,
                new JwtIssuerValidator(AccessTokens.ISSUER)));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(SecurityProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secretKey(properties)));
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** The {@code roles} claim becomes ROLE_ authorities, so routes can require {@code hasRole("ORGANIZER")}. */
    private static JwtAuthenticationConverter rolesFromToken() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName(AccessTokens.ROLES_CLAIM);
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }

    private static SecretKey secretKey(SecurityProperties properties) {
        return new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
