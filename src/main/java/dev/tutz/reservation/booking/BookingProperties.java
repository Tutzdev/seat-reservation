package dev.tutz.reservation.booking;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param holdDuration how long held seats are kept before they go back on sale
 * @param maxSeatsPerBooking caps a single request, so one client cannot hold a whole section
 */
@ConfigurationProperties("reservation.bookings")
public record BookingProperties(Duration holdDuration, int maxSeatsPerBooking) {
}
