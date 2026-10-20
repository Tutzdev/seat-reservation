package dev.tutz.reservation.common;

/** The same answer for an unknown e-mail and a wrong password, so it does not reveal which accounts exist. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid e-mail or password.");
    }
}
