package com.airticket.exception;

/** Maps to HTTP 409 — the request was well-formed but conflicts with current state. */
public class InsufficientSeatsException extends RuntimeException {

    private final int requested;
    private final int available;

    public InsufficientSeatsException(int requested, int available) {
        super("Requested %d seat(s) but only %d remain on this flight".formatted(requested, available));
        this.requested = requested;
        this.available = available;
    }

    public int getRequested() {
        return requested;
    }

    public int getAvailable() {
        return available;
    }
}
