package dev.tutz.reservation.customer;

import java.time.Instant;
import java.util.UUID;

public record CustomerResponse(UUID id, String email, String name, Role role, Instant createdAt) {

    static CustomerResponse from(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getEmail(), customer.getName(), customer.getRole(),
                customer.getCreatedAt());
    }
}
