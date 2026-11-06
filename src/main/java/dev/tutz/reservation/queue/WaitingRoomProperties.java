package dev.tutz.reservation.queue;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** @param passTtl how long a customer who was let in may start holding seats before the pass expires */
@Validated
@ConfigurationProperties("reservation.waiting-room")
public record WaitingRoomProperties(@NotNull Duration passTtl) {
}
