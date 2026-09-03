package com.aerocore.exception;

/**
 * Thrown when a payment attempt has no confirmed outcome.
 *
 * <p>The gateway did not give us enough information to know whether money moved.
 * The booking therefore remains in PAYMENT_PENDING for reconciliation and its
 * seats are not released.
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