package dev.tutz.reservation.booking;

import dev.tutz.reservation.common.BusinessRuleException;
import dev.tutz.reservation.common.PageResponse;
import dev.tutz.reservation.common.ResourceNotFoundException;
import dev.tutz.reservation.event.SeatRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

    private final SeatHolder holder;
    private final BookingRepository bookings;
    private final SeatRepository seats;
    private final PaymentRepository payments;
    private final Clock clock;

    public BookingService(SeatHolder holder, BookingRepository bookings, SeatRepository seats,
            PaymentRepository payments, Clock clock) {
        this.holder = holder;
        this.bookings = bookings;
        this.seats = seats;
        this.payments = payments;
        this.clock = clock;
    }

    /**
     * Makes holds safe to retry: the same Idempotency-Key returns the booking that already exists instead
     * of creating a second one. Only successful holds are remembered; a request that lost its seats rolled
     * back and can be retried with the same key.
     *
     * <p>Keys belong to the customer who sent them. Another customer using the same key by chance makes an
     * unrelated booking and never sees this one.
     */
    public HoldResult hold(UUID customerId, HoldRequest request, String idempotencyKey) {
        HoldCommand command = HoldCommand.of(customerId, request, idempotencyKey);
        Optional<HoldResult> replay = replayOf(command);
        if (replay.isPresent()) {
            return replay.get();
        }

        try {
            return HoldResult.created(holder.hold(command));
        } catch (DataIntegrityViolationException exception) {
            // Lost the race against a request that committed first: a retry with the same key, or another
            // hold of this customer for the same event.
            return replayOf(command).orElseThrow(() -> bookings
                    .findByCustomerIdAndEventIdAndStatus(command.customerId(), command.eventId(), BookingStatus.HELD)
                    .<RuntimeException>map(held -> SeatHolder.holdAlreadyActive(held.getId()))
                    .orElse(exception));
        }
    }

    @Transactional
    public BookingResponse cancel(UUID customerId, UUID bookingId) {
        Instant now = clock.instant();
        Booking booking = bookings.lockById(bookingId).orElseThrow(() -> notFound(bookingId));
        booking.requireOwner(customerId);
        if (booking.cancel(now)) {
            seats.releaseAll(List.of(bookingId));
        }
        return respond(booking, now);
    }

    @Transactional(readOnly = true)
    public BookingResponse find(UUID customerId, UUID bookingId) {
        Instant now = clock.instant();
        Booking booking = bookings.findById(bookingId).orElseThrow(() -> notFound(bookingId));
        booking.requireOwner(customerId);
        return respond(booking, now);
    }

    /** The customer's bookings, newest first, with their payments fetched in one query for the whole page. */
    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> mine(UUID customerId, Pageable pageable) {
        Instant now = clock.instant();
        Page<Booking> page = bookings.findByCustomerId(customerId, pageable);
        Map<UUID, Payment> paymentsByBooking = payments.findAllById(page.map(Booking::getId).toList()).stream()
                .collect(Collectors.toMap(Payment::getBookingId, Function.identity()));
        return PageResponse.from(page.map(booking -> BookingResponse.from(booking,
                paymentsByBooking.get(booking.getId()), now)));
    }

    /** Any booking, whoever owns it: for callers that already checked who is asking. */
    @Transactional(readOnly = true)
    BookingResponse find(UUID bookingId) {
        Instant now = clock.instant();
        return bookings.findById(bookingId)
                .map(booking -> respond(booking, now))
                .orElseThrow(() -> notFound(bookingId));
    }

    private Optional<HoldResult> replayOf(HoldCommand command) {
        return bookings.findByCustomerIdAndIdempotencyKey(command.customerId(), command.idempotencyKey())
                .map(existing -> {
                    if (!existing.wasRequestedWith(command.fingerprint())) {
                        throw new BusinessRuleException("IDEMPOTENCY_KEY_REUSED",
                                "This Idempotency-Key was already used for a different request.");
                    }
                    return HoldResult.replayed(respond(existing, clock.instant()));
                });
    }

    private BookingResponse respond(Booking booking, Instant now) {
        return BookingResponse.from(booking, payments.findById(booking.getId()).orElse(null), now);
    }

    private static ResourceNotFoundException notFound(UUID bookingId) {
        return new ResourceNotFoundException("Booking %s not found.".formatted(bookingId));
    }
}
