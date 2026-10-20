package dev.tutz.reservation.customer;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Creates the first organizer from ORGANIZER_EMAIL / ORGANIZER_PASSWORD, so no credential lives in the code. */
@Component
class OrganizerBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OrganizerBootstrap.class);

    private final CustomerRepository customers;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties properties;
    private final Clock clock;

    OrganizerBootstrap(CustomerRepository customers, PasswordEncoder passwordEncoder, SecurityProperties properties,
            Clock clock) {
        this.customers = customers;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = properties.organizerEmail();
        String password = properties.organizerPassword();
        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            log.info("No organizer credentials configured; skipping organizer bootstrap.");
            return;
        }
        if (password.length() < SecurityProperties.MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("ORGANIZER_PASSWORD must have at least %d characters."
                    .formatted(SecurityProperties.MIN_PASSWORD_LENGTH));
        }
        if (customers.existsByEmail(Customer.normalize(email))) {
            return;
        }

        customers.save(Customer.register(email, "Organizer", passwordEncoder.encode(password), Role.ORGANIZER,
                clock.instant()));
        log.info("Organizer account created.");
    }
}
