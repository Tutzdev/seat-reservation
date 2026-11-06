package dev.tutz.reservation.ratelimit;

import dev.tutz.reservation.common.TooManyRequestsException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Limits how many requests each logged-in customer sends, whatever the endpoint. It runs right after the access
 * token is read, so the limit follows the customer id across devices and instances, not an IP address that a
 * crowd behind one carrier shares. Anonymous requests are not counted here; logins have their own limit.
 *
 * <p>Not a Spring bean on purpose: as a bean, Spring Boot would also register it on the servlet container and it
 * would run twice. {@code SecurityConfiguration} creates it inside the security chain.
 */
public class CustomerRateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;
    private final RateLimitProperties properties;
    private final Clock clock;
    private final HandlerExceptionResolver errors;

    public CustomerRateLimitFilter(RateLimiter limiter, RateLimitProperties properties, Clock clock,
            HandlerExceptionResolver errors) {
        this.limiter = limiter;
        this.properties = properties;
        this.clock = clock;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken customer) {
            Duration wait = limiter.acquire("customer:" + customer.getName(), properties.customer(), clock.instant());
            if (!wait.isZero()) {
                // Answered by the MVC exception handlers, as the same Problem Details body as every other error.
                errors.resolveException(request, response, null, new TooManyRequestsException("TOO_MANY_REQUESTS",
                        "Too many requests from this account. Slow down and try again shortly.", wait));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
