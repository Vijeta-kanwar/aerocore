package com.aerocore.service;

import com.aerocore.dto.BookingRequest;
import com.aerocore.exception.IllegalBookingTransitionException;
import com.aerocore.exception.InsufficientSeatsException;
import com.aerocore.exception.ResourceNotFoundException;
import com.aerocore.model.Booking;
import com.aerocore.model.BookingStatus;
import com.aerocore.model.Flight;
import com.aerocore.repository.BookingRepository;
import com.aerocore.repository.FlightRepository;
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
     * Reserving seats is a conditional atomic update, not lock-read-check-write.
     *
     * <p>The seat decision belongs to the database: reserveSeats decrements only if enough
     * remain, and reports whether it did. Nothing is read into Java and then acted upon, so
     * there is no window for another booking to change the count underneath us.
     *
     * <p>The read below still happens, but not to decide anything. It answers a different
     * question -- does this flight exist at all -- because reserveSeats returning zero cannot
     * tell "full" from "no such flight", and those are a 409 and a 404.
     */
    @Transactional
    public Booking create(BookingRequest request) {
        // Establishes existence and gives us the price. Its seat count is deliberately unused:
        // any value read separately from the write is stale the moment we hold it.
        Flight flight = flightRepository.findById(request.flightId())
                .orElseThrow(() -> ResourceNotFoundException.flight(request.flightId()));

        BigDecimal total = flight.getPrice().multiply(BigDecimal.valueOf(request.seatsBooked()));

        int reserved = flightRepository.reserveSeats(request.flightId(), request.seatsBooked());
        if (reserved == 0) {
            // We already know the flight exists, so zero rows can only mean it is too full.
            // This re-read is honest data: reserveSeats cleared the persistence context, so it
            // hits the database instead of handing back the stale object from above.
            int remaining = flightRepository.findById(request.flightId())
                    .map(Flight::getAvailableSeats)
                    .orElse(0);
            throw new InsufficientSeatsException(request.seatsBooked(), remaining);
        }

        // flight is detached now -- the clear evicted it -- and that is fine here. A @ManyToOne
        // only needs the id to write the foreign key, and the price was already loaded.
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

    /**
     * Cancel still takes an explicit lock, and that is not inconsistency.
     *
     * <p>Releasing a seat has no condition to push into a WHERE clause -- there is no "only if"
     * about giving one back. What it does have is a read-decide-write spanning two entities,
     * which is exactly the shape a transaction-long lock exists to protect.
     */
    @Transactional
    public Booking cancel(Long id) {
        Booking booking = findById(id);
        BookingStatus current = booking.getStatus();

        // Checked before the lock, not after: a doomed request should not make everyone else
        // queue behind a row lock it was never going to use.
        if (!current.canTransitionTo(BookingStatus.CANCELLED)) {
            throw new IllegalBookingTransitionException(booking.getReference(), current, BookingStatus.CANCELLED);
        }

        if (current.releasesSeatsOnTransitionTo(BookingStatus.CANCELLED)) {
            Flight flight = flightRepository.findByIdForUpdate(booking.getFlight().getId())
                    .orElseThrow(() -> ResourceNotFoundException.flight(booking.getFlight().getId()));
            flight.releaseSeats(booking.getSeatsBooked());
        }

        // Both entities are managed here; the seat credit and the status change commit together.
        booking.cancel();

        return booking;
    }

    @Transactional
    public void delete(Long id) {
        Booking booking = findById(id);

        // Ask the state, not "is it cancelled". An expired hold has already returned its seats
        // and was never cancelled, so the old check would have credited them twice.
        if (booking.holdsSeats()) {
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
