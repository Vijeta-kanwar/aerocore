package com.aerocore.service;

import com.aerocore.exception.ResourceNotFoundException;
import com.aerocore.model.Booking;
import com.aerocore.model.Flight;
import com.aerocore.repository.BookingRepository;
import com.aerocore.repository.FlightRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.aerocore.dto.BookingResponse;

/**
 * The short transactions that bracket a payment.
 *
 * <p>Each method here opens a transaction, does a handful of writes, and commits. None of
 * them wait on anything external. That is the whole point: the gateway call happens between
 * these methods, not inside them, so no connection is held and no row is locked while a
 * third party thinks.
 */
@Service
public class BookingPaymentService {

    private final BookingRepository bookingRepository;
    private final FlightRepository flightRepository;

    public BookingPaymentService(BookingRepository bookingRepository, FlightRepository flightRepository) {
        this.bookingRepository = bookingRepository;
        this.flightRepository = flightRepository;
    }

    /**
     * Moves a hold out of the sweeper's reach before the gateway is called.
     *
     * <p>Committing this before charging is the ordering that matters. The other way round --
     * charge first, mark second -- leaves a window where the sweeper can expire a booking
     * that has already been paid for, and no amount of care elsewhere closes it.
     */
    @Transactional
    public Booking beginPayment(Long bookingId) {
        Booking booking = load(bookingId);
        booking.beginPayment();
        return booking;
    }

    @Transactional
public BookingResponse confirmPaid(Long bookingId, String chargeId) {
    Booking booking = load(bookingId);
    booking.confirm(chargeId);
    return BookingResponse.from(booking);
}

    /**
     * Gives the seats back after a payment we know failed.
     *
     * <p>Only ever called for a definite decline. An unknown outcome leaves the booking in
     * PAYMENT_PENDING for the reconciler, because "the gateway didn't answer" and "the charge
     * didn't happen" are different facts and only one of them makes it safe to resell.
     */
    @Transactional
    public BookingResponse releaseAfterFailedPayment(Long bookingId)  {
        Booking booking = load(bookingId);

        if (booking.holdsSeats()) {
            Flight flight = flightRepository.findByIdForUpdate(booking.getFlight().getId())
                    .orElseThrow(() -> ResourceNotFoundException.flight(booking.getFlight().getId()));
            flight.releaseSeats(booking.getSeatsBooked());
        }

        booking.cancel();
    return BookingResponse.from(booking);

    }

    private Booking load(Long bookingId) {
        return bookingRepository.findById(bookingId)
                .orElseThrow(() -> ResourceNotFoundException.booking(bookingId));
    }
}
