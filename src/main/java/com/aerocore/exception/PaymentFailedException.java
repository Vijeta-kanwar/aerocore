package com.aerocore.exception;

/**
 * Thrown when a checkout cannot be completed because the payment didn't succeed.
 *
 * <p>Covers two quite different situations on purpose: a clean decline, where the seats have
 * already gone back, and an unknown outcome, where they deliberately haven't. The passenger
 * gets a message they can act on either way; which one it was matters to the reconciler, not
 * to them.
 */
public class PaymentFailedException extends RuntimeException {

    private final String reference;

    public PaymentFailedException(String reference, String message) {
        super(message);
        this.reference = reference;
    }

    public String getReference() {
        return reference;
    }
}
