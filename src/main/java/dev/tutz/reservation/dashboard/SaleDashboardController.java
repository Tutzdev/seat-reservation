package dev.tutz.reservation.dashboard;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Organizers only: the numbers behind the live view of a sale. Clients poll it every few seconds. */
@RestController
public class SaleDashboardController {

    private final SaleDashboardService dashboard;

    public SaleDashboardController(SaleDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/v1/events/{eventId}/dashboard")
    public SaleDashboardResponse dashboard(@PathVariable UUID eventId) {
        return dashboard.of(eventId);
    }
}
