package com.airticket.exception;

/** Maps to HTTP 409 — cancelling twice would release seats twice. */
public class BookingAlreadyCancelledException extends RuntimeException {

    public BookingAlreadyCancelledException(String reference) {
        super("Booking " + reference + " is already cancelled");
    }
}
