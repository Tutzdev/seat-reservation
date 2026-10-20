package dev.tutz.reservation.customer;

/** @param expiresIn seconds until the access token stops being accepted */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
}
