package dev.tutz.reservation.event;

/** How an event's seats stand right now. Every event has seats, so the sums behind it are never null. */
public record SeatCounts(long total, long available, long held, long sold) {
}
