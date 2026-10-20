package dev.tutz.reservation.customer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = SecurityProperties.MIN_PASSWORD_LENGTH, max = 128) String password,
        @NotBlank @Size(max = 120) String name) {
}
