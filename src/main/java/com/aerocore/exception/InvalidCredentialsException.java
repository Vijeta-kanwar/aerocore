package com.aerocore.exception;

/**
 * Thrown when a login fails, for any reason.
 *
 * <p>One exception and one message for both "no such account" and "wrong password", on
 * purpose. Distinguishing them turns the login form into an account-enumeration oracle:
 * an attacker learns which addresses are registered without ever guessing a password.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Email or password is incorrect");
    }
}
