package dev.tutz.reservation.booking;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private final BookingService bookings;

    public BookingController(BookingService bookings) {
        this.bookings = bookings;
    }

    @PostMapping
    public ResponseEntity<BookingResponse> hold(@Valid @RequestBody HoldRequest request) {
        BookingResponse booking = bookings.hold(request);
        return ResponseEntity.created(URI.create("/api/v1/bookings/" + booking.id())).body(booking);
    }

    @GetMapping("/{id}")
    public BookingResponse find(@PathVariable UUID id) {
        return bookings.find(id);
    }
}
