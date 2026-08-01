package com.airticket.service;

import com.airticket.dto.BookingRequest;
import com.airticket.exception.BookingAlreadyCancelledException;
import com.airticket.exception.InsufficientSeatsException;
import com.airticket.exception.ResourceNotFoundException;
import com.airticket.model.Booking;
import com.airticket.model.Flight;
import com.airticket.repository.BookingRepository;
import com.airticket.repository.FlightRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class BookingService {

    private final BookingRepository bookingRepository;
    private final FlightRepository flightRepository;
    private final BookingReferenceGenerator referenceGenerator;

    public BookingService(BookingRepository bookingRepository,
                          FlightRepository flightRepository,
                          BookingReferenceGenerator referenceGenerator) {
        this.bookingRepository = bookingRepository;
        this.flightRepository = flightRepository;
        this.referenceGenerator = referenceGenerator;
    }

    public List<Booking> findAll() {
        return bookingRepository.findAll();
    }

    public Booking findById(Long id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.booking(id));
    }

    public Booking findByReference(String reference) {
        return bookingRepository.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("No booking exists with reference " + reference));
    }

    public List<Booking> findByEmail(String email) {
        return bookingRepository.findByPassengerEmailIgnoreCaseOrderByBookedAtDesc(email.trim());
    }

    /**
     * Reserving a seat is read-then-write on a shared counter, so the whole operation
     * runs in one transaction and takes a row lock on the flight first. Any other
     * replica attempting the same flight blocks until this commits.
     */
    @Transactional
    public Booking create(BookingRequest request) {
        Flight flight = flightRepository.findByIdForUpdate(request.flightId())
                .orElseThrow(() -> ResourceNotFoundException.flight(request.flightId()));

        if (!flight.hasSeatsFor(request.seatsBooked())) {
            throw new InsufficientSeatsException(request.seatsBooked(), flight.getAvailableSeats());
        }

        // No explicit save: flight is a managed entity inside this transaction, so the
        // seat decrement is flushed by dirty checking at commit — atomically with the
        // booking insert below.
        flight.reserveSeats(request.seatsBooked());

        BigDecimal total = flight.getPrice().multiply(BigDecimal.valueOf(request.seatsBooked()));

        Booking booking = new Booking(
                nextReference(),
                flight,
                request.passengerName().trim(),
                request.passengerEmail().trim().toLowerCase(),
                request.passengerPhone().trim(),
                request.seatsBooked(),
                total);

        return bookingRepository.save(booking);
    }

    @Transactional
    public Booking cancel(Long id) {
        Booking booking = findById(id);

        if (booking.isCancelled()) {
            throw new BookingAlreadyCancelledException(booking.getReference());
        }

        // Lock the flight before touching its seat counter, same reasoning as create().
        Flight flight = flightRepository.findByIdForUpdate(booking.getFlight().getId())
                .orElseThrow(() -> ResourceNotFoundException.flight(booking.getFlight().getId()));

        // Both entities are managed here; the seat credit and the status change commit together.
        flight.releaseSeats(booking.getSeatsBooked());
        booking.cancel();

        return booking;
    }

    @Transactional
    public void delete(Long id) {
        Booking booking = findById(id);
        if (!booking.isCancelled()) {
            Flight flight = flightRepository.findByIdForUpdate(booking.getFlight().getId())
                    .orElseThrow(() -> ResourceNotFoundException.flight(booking.getFlight().getId()));
            flight.releaseSeats(booking.getSeatsBooked());
        }
        bookingRepository.delete(booking);
    }

    private String nextReference() {
        String reference = referenceGenerator.generate();
        while (bookingRepository.existsByReference(reference)) {
            reference = referenceGenerator.generate();
        }
        return reference;
    }
}
