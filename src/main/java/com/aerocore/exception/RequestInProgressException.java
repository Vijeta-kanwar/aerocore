package com.aerocore.exception;

/**
 * Thrown when a duplicate arrives while the original request is still running.
 *
 * <p>The honest answer to "did it work?" is "I don't know yet". We can't return a booking
 * that hasn't committed, and we mustn't start a second one, so the caller is told to wait
 * and ask again with the same key.
 */
public class RequestInProgressException extends RuntimeException {

    public RequestInProgressException(String key) {
        super("A request with Idempotency-Key %s is still being processed. Retry shortly with the same key.".formatted(key));
    }
}
