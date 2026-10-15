package dev.tutz.reservation.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/** A missing ledger setting stops the application at startup, not at the first customer's payment. */
class LedgerPropertiesTests {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(LedgerSettings.class)
            .withPropertyValues(
                    "reservation.payments.ledger.base-url=http://localhost:8081",
                    "reservation.payments.ledger.email=tickets@ledger.test",
                    "reservation.payments.ledger.password=secret-password",
                    "reservation.payments.ledger.connect-timeout=2s",
                    "reservation.payments.ledger.read-timeout=5s");

    @Test
    void startsWhenEverySettingIsThere() {
        context.withPropertyValues("reservation.payments.ledger.merchant-account-id=00000000-0000-0000-0000-00000000c0de")
                .run(started -> assertThat(started).hasNotFailed());
    }

    @Test
    void refusesToStartWithoutTheMerchantAccount() {
        context.withPropertyValues("reservation.payments.ledger.merchant-account-id=")
                .run(started -> assertThat(started).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("merchantAccountId"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LedgerProperties.class)
    static class LedgerSettings {
    }
}
