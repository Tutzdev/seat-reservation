package dev.tutz.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReservationApplicationTests extends PostgresTestSupport {

    @Autowired
    private Flyway flyway;

    @Test
    void startsWithEveryMigrationApplied() {
        assertThat(flyway.info().pending()).isEmpty();
    }
}
