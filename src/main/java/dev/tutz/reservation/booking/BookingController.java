package dev.tutz.reservation.booking;

import dev.tutz.reservation.common.PageRequests;
import dev.tutz.reservation.common.PageResponse;
import dev.tutz.reservation.customer.CurrentCustomer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Every booking belongs to the customer in the access token; someone else's booking answers 404. */
@RestController
@RequestMapping("/api/v1/bookings")
public class BookingController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String KEY_FORMAT = "[A-Za-z0-9_-]{8,64}";

    private final BookingService bookings;
    private final PaymentService payments;

    public BookingController(BookingService bookings, PaymentService payments) {
        this.bookings = bookings;
        this.payments = payments;
    }

    /** 201 for a new booking; 200 plus Idempotent-Replayed for a retry of one that already exists. */
    @PostMapping
    public ResponseEntity<BookingResponse> hold(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(IDEMPOTENCY_KEY) @Pattern(regexp = KEY_FORMAT) String idempotencyKey,
            @Valid @RequestBody HoldRequest request) {
        HoldResult result = bookings.hold(CurrentCustomer.idOf(jwt), request, idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.booking());
        }
        return ResponseEntity.created(URI.create("/api/v1/bookings/" + result.booking().id())).body(result.booking());
    }

    @GetMapping
    public PageResponse<BookingResponse> mine(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return bookings.mine(CurrentCustomer.idOf(jwt),
                PageRequests.create(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
    }

    @GetMapping("/{id}")
    public BookingResponse find(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return bookings.find(CurrentCustomer.idOf(jwt), id);
    }

    @PostMapping("/{id}/cancel")
    public BookingResponse cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return bookings.cancel(CurrentCustomer.idOf(jwt), id);
    }

    /**
     * Charges the hold through wallet-ledger and confirms it. Needs no Idempotency-Key header: the booking id is
     * the key, so a retry after a timeout or a 503 settles the same payment instead of charging twice.
     */
    @PostMapping("/{id}/payment")
    public BookingResponse pay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody PayRequest request) {
        return payments.pay(CurrentCustomer.idOf(jwt), id, request);
    }
}
