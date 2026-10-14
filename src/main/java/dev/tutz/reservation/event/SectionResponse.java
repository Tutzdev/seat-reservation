package dev.tutz.reservation.event;

public record SectionResponse(String name, long seats, long available, long priceCents) {
}
