package dev.tutz.reservation.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** @param maxSeatsPerEvent upper bound for one seat map, so a single request cannot insert millions of rows */
@ConfigurationProperties("reservation.events")
public record EventProperties(int maxSeatsPerEvent) {
}
