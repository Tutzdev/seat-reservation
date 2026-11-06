package dev.tutz.reservation.queue;

import dev.tutz.reservation.customer.CurrentCustomer;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The waiting room of an event's sale. Clients join once, then poll until {@code admitted} is true. */
@RestController
@RequestMapping("/api/v1/events/{eventId}/queue")
public class QueueController {

    private final QueueService queue;

    public QueueController(QueueService queue) {
        this.queue = queue;
    }

    @PostMapping
    public QueueResponse join(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        return queue.join(eventId, CurrentCustomer.idOf(jwt));
    }

    @GetMapping
    public QueueResponse status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID eventId) {
        return queue.status(eventId, CurrentCustomer.idOf(jwt));
    }
}
