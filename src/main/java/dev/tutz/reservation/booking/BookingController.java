package dev.tutz.reservation.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String KEY_FORMAT = "[A-Za-z0-9_-]{8,64}";

    private final BookingService bookings;

    public BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    /** 201 for a new booking; 200 plus Idempotent-Replayed for a retry of one that already exists. */
    @PostMapping
    public ResponseEntity<BookingResponse> hold(
            @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = KEY_FORMAT) String idempotencyKey,
            @Valid @RequestBody HoldRequest request) {
        HoldResult result = bookings.hold(request, idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.booking());
        }
        return ResponseEntity.created(URI.create("/api/v1/bookings/" + result.booking().id())).body(result.booking());
    }

    @GetMapping("/{id}")
    public BookingResponse find(@PathVariable UUID id) {
        return bookings.find(id);
    }

    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@PathVariable UUID id) {
        return bookings.cancel(id);
    }
}
