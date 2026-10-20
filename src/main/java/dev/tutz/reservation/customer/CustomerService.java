package dev.tutz.reservation.customer;

import dev.tutz.reservation.common.ConflictException;
import dev.tutz.reservation.common.InvalidCredentialsException;
import dev.tutz.reservation.common.ResourceNotFoundException;
import java.time.Clock;
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
    private final Clock clock;
    private final String dummyPasswordHash;

    public CustomerService(CustomerRepository customers, PasswordEncoder passwordEncoder, AccessTokens tokens,
            Clock clock) {
        this.customers = customers;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
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

    @Transactional(readOnly = true)
    public TokenResponse issueToken(TokenRequest request) {
        Customer customer = customers.findByEmail(Customer.normalize(request.email())).orElse(null);
        // BCrypt runs even for unknown e-mails, so response time does not reveal which accounts exist.
        String hash = customer != null ? customer.getPasswordHash() : dummyPasswordHash;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
        if (customer == null || !passwordMatches) {
            throw new InvalidCredentialsException();
        }
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
