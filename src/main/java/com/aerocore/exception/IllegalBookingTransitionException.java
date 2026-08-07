package com.aerocore.exception;

import com.aerocore.model.BookingStatus;

/**
 * Thrown when a booking is asked to make a move its current state doesn't allow.
 *
 * <p>Replaces IllegalBookingTransitionException, which covered one case out of many.
 * Cancelling twice, confirming an expired hold and expiring a paid booking are all
 * the same mistake -- a caller assuming a state the booking isn't in -- so they get
 * one exception and one 409. Fewer types, one rule, and a message that names both
 * ends of the attempted move instead of just the reference.
 */
public class IllegalBookingTransitionException extends RuntimeException {

    private final String reference;
    private final BookingStatus from;
    private final BookingStatus to;

    public IllegalBookingTransitionException(String reference, BookingStatus from, BookingStatus to) {
        super("Booking %s is %s and cannot become %s".formatted(reference, from, to));
        this.reference = reference;
        this.from = from;
        this.to = to;
    }

    public String getReference() {
        return reference;
    }

    public BookingStatus getFrom() {
        return from;
    }

    public BookingStatus getTo() {
        return to;
    }
}
