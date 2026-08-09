package com.aerocore.exception;

/**
 * Thrown when a key is replayed with a different request body than the one that created it.
 *
 * <p>Deliberately not a silent replay. A network retry sends the same body; a client bug
 * sends a different one. Returning the original booking would leave someone holding a seat
 * on a flight they didn't ask for, and nobody would ever find out.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key %s was already used for a different request".formatted(key));
    }
}
