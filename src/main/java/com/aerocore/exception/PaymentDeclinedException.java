package com.aerocore.exception;

/**
 * Thrown when the payment gateway gives a definite decline.
 *
 * <p>The outcome is settled: no money moved and the seats have already been
 * released. A replay of the same idempotency key must reproduce this failure.
 */
public class PaymentDeclinedException extends PaymentFailedException {

    public PaymentDeclinedException(String reference, String message) {
        super(reference, message);
    }
}