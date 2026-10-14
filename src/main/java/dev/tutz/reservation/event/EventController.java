package dev.tutz.reservation.event;

import dev.tutz.reservation.common.PageRequests;
import dev.tutz.reservation.common.PageResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private final EventService events;

    public EventController(EventService events) {
        this.events = events;
    }

    @PostMapping
    public ResponseEntity<EventResponse> create(@Valid @RequestBody CreateEventRequest request) {
        EventResponse event = events.create(request);
        return ResponseEntity.created(URI.create("/api/v1/events/" + event.id())).body(event);
    }

    @GetMapping
    public PageResponse<EventSummaryResponse> upcoming(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return events.upcoming(PageRequests.create(page, size, Sort.by("startsAt", "id")));
    }

    @GetMapping("/{id}")
    public EventResponse find(@PathVariable UUID id) {
        return events.find(id);
    }

    @GetMapping("/{id}/seats")
    public List<SeatResponse> seatMap(@PathVariable UUID id, @RequestParam(required = false) String section) {
        return events.seatMap(id, section);
    }
}
