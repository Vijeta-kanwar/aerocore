package com.aerocore.exception;

/**
 * Thrown when a caller asks to act on a booking that isn't theirs.
 *
 * <p>Mapped to 404 rather than 403. A 403 confirms the booking exists, which lets anyone
 * enumerate ids and learn how many bookings the system holds. To someone who doesn't own it,
 * a booking they can't see and a booking that doesn't exist should be indistinguishable.
 */
public class BookingAccessDeniedException extends RuntimeException {

    public BookingAccessDeniedException(Long bookingId) {
        super("No booking exists with id " + bookingId);
    }
}
