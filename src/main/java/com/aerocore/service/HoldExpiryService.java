package com.aerocore.service;

import com.aerocore.model.Booking;
import com.aerocore.model.Flight;
import com.aerocore.repository.BookingRepository;
import com.aerocore.repository.FlightRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Reclaims seats from holds nobody paid for.
 *
 * <p>Without this, a user who opens the booking form and closes the tab takes a seat with
 * them permanently. The seat is neither sold nor available, and nothing in the system ever
 * notices -- the booking is in a perfectly valid state, it just never leaves it.
 */
@Service
public class HoldExpiryService {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryService.class);

    private final BookingRepository bookingRepository;
    private final FlightRepository flightRepository;

    public HoldExpiryService(BookingRepository bookingRepository, FlightRepository flightRepository) {
        this.bookingRepository = bookingRepository;
        this.flightRepository = flightRepository;
    }

    /**
     * Expires one batch of dead holds and returns how many it handled.
     *
     * <p>Both writes -- the status change and the seat credit -- live in this one
     * transaction, which is what makes the crash-ordering question disappear. Marking a
     * booking EXPIRED without crediting its seats loses a seat forever; crediting without
     * marking lets the next sweep credit it again. Neither can happen if the only two
     * outcomes are "both" and "neither".
     *
     * <p>One batch per transaction rather than one giant sweep: locks are held for
     * milliseconds instead of minutes, a failure costs one batch instead of everything, and
     * a connection isn't tied up while fifty thousand rows go through.
     */
    @Transactional
    public int expireBatch(int batchSize) {
        List<Booking> holds = bookingRepository.claimExpiredHolds(Instant.now(), batchSize);

        for (Booking hold : holds) {
            // The claim query returned this row under a lock we hold, so no other replica can
            // be looking at it. releaseSeats clamps at capacity, and expire() refuses any
            // status that isn't PENDING -- three layers agreeing, none of them load-bearing
            // alone.
            Flight flight = flightRepository.findByIdForUpdate(hold.getFlight().getId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Booking " + hold.getReference() + " references a flight that no longer exists"));

            flight.releaseSeats(hold.getSeatsBooked());
            hold.expire();

            log.info("Expired hold {} and returned {} seat(s) to flight {}",
                    hold.getReference(), hold.getSeatsBooked(), flight.getFlightNumber());
        }

        return holds.size();
    }
}
