package dev.tutz.reservation.customer;

import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.common.InvalidCredentialsException;
import dev.tutz.reservation.common.ResourceNotFoundException;
import dev.tutz.reservation.common.TooManyRequestsException;
import dev.tutz.reservation.ratelimit.RateLimitProperties;
import dev.tutz.reservation.ratelimit.RateLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokens tokens;
    private final RateLimiter rateLimiter;
    private final RateLimitProperties rateLimits;
    private final Clock clock;
    private final String dummyPasswordHash;

    public CustomerService(CustomerRepository customers, PasswordEncoder passwordEncoder, AccessTokens tokens,
            RateLimiter rateLimiter, RateLimitProperties rateLimits, Clock clock) {
        this.customers = customers;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.rateLimiter = rateLimiter;
        this.rateLimits = rateLimits;
        this.clock = clock;
        this.dummyPasswordHash = passwordEncoder.encode("timing-equalizer");
    }

    /** Self-service sign-up always creates a {@link Role#CUSTOMER}; organizers are created by configuration. */
    public CustomerResponse register(RegisterRequest request) {
        String email = Customer.normalize(request.email());
        if (customers.existsByEmail(email)) {
            throw emailTaken();
        }
        Customer customer = Customer.register(email, request.name(), passwordEncoder.encode(request.password()),
                Role.CUSTOMER, clock.instant());
        try {
            return CustomerResponse.from(customers.saveAndFlush(customer));
        } catch (DataIntegrityViolationException exception) {
            // Two sign-ups with the same e-mail at once: the unique constraint lets only one through.
            throw emailTaken();
        }
    }

    /**
     * Failed attempts are limited per e-mail, so a password cannot be guessed by trying fast. Every attempt takes
     * one from the allowance before the password is checked, which also holds for attempts that arrive together,
     * and a successful one gives it back: only failures count. Unknown e-mails are limited the same way.
     */
    @Transactional(readOnly = true)
    public TokenResponse issueToken(TokenRequest request) {
        Instant now = clock.instant();
        String email = Customer.normalize(request.email());
        String attempts = "login:" + email;
        Duration wait = rateLimiter.acquire(attempts, rateLimits.login(), now);
        if (!wait.isZero()) {
            throw new TooManyRequestsException("TOO_MANY_LOGIN_ATTEMPTS",
                    "Too many failed logins for this e-mail. Try again later.", wait);
        }

        Customer customer = customers.findByEmail(email).orElse(null);
        // BCrypt runs even for unknown e-mails, so response time does not reveal which accounts exist.
        String hash = customer != null ? customer.getPasswordHash() : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (customer == null || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
        rateLimiter.release(attempts, rateLimits.login(), now);
        return tokens.issue(customer);
    }

    @Transactional(readOnly = true)
    public CustomerResponse find(UUID customerId) {
        return customers.findById(customerId)
                .map(CustomerResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Customer %s not found.".formatted(customerId)));
    }

    private static ConflictException emailTaken() {
        return new ConflictException("EMAIL_ALREADY_REGISTERED", "This e-mail is already registered.", Map.of());
    }
}
