package com.aerocore.service;

import com.aerocore.model.Booking;
import com.aerocore.model.BookingStatus;
import com.aerocore.model.Flight;
import com.aerocore.payment.PaymentGateway.PaymentResult;
import com.aerocore.repository.BookingRepository;
import com.aerocore.repository.FlightRepository;
import com.aerocore.dto.BookingResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aerocore.dto.PaymentFailureResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The short transactions that settle a payment whose outcome we never learned.
 *
 * <p>Deliberately does not call the gateway itself. That call belongs outside any
 * transaction, for the same reasons checkout learned the hard way -- so the reconciler reads
 * candidates, asks the gateway on its own time, and comes back here with an answer.
 */
@Service
public class PaymentReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciliationService.class);

    private final BookingRepository bookingRepository;
    private final FlightRepository flightRepository;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

public PaymentReconciliationService(BookingRepository bookingRepository,
                                    FlightRepository flightRepository,
                                    IdempotencyService idempotencyService,
                                    ObjectMapper objectMapper) {
    this.bookingRepository = bookingRepository;
    this.flightRepository = flightRepository;
    this.idempotencyService = idempotencyService;
    this.objectMapper = objectMapper;
}


    /**
     * Applies what the gateway told us, if the booking is still waiting to hear it.
     *
     * <p>The status re-check is the whole concurrency story. Two replicas can look up the same
     * reference at the same moment and both come back with SUCCEEDED -- lookups are reads, so
     * nothing stops them. The first one to get here confirms the booking; the second finds it
     * is no longer PAYMENT_PENDING and does nothing. Same guard as the conditional seat update:
     * the state decides, not a lock.
     *
     * <p>We accept those duplicate lookups on purpose. The alternative is holding a row lock
     * across a network call to a third party, which is precisely the trade checkout refuses.
     */
    @Transactional
    public boolean applyOutcome(Long bookingId, PaymentResult result) {
        Optional<Booking> found = bookingRepository.findById(bookingId);
        if (found.isEmpty()) {
            return false;
        }

        Booking booking = found.get();
        if (booking.getStatus() != BookingStatus.PAYMENT_PENDING) {
            // Someone else resolved it between our read and now. Nothing to do, and nothing wrong.
            return false;
        }

        return switch (result.outcome()) {
            case SUCCEEDED -> {
    booking.confirm(result.chargeId());

    try {
        String responseBody = objectMapper.writeValueAsString(
                BookingResponse.from(booking)
        );

        idempotencyService.completeCheckoutByBookingId(
                booking.getId(),
                responseBody
        );
    } catch (JsonProcessingException e) {
        throw new IllegalStateException(
                "Could not serialize reconciled booking "
                        + booking.getReference(), e);
    }

    log.info(
            "Reconciled {}: the charge had succeeded after all",
            booking.getReference()
    );

    yield true;
}
            case DECLINED, NOT_FOUND -> {
    releaseSeats(booking);
    booking.cancel();

    try {
        String responseBody = objectMapper.writeValueAsString(
                new PaymentFailureResponse(
                        booking.getReference(),
                        result.message()
                )
        );

        idempotencyService.failCheckoutByBookingId(
                booking.getId(),
                responseBody
        );
    } catch (JsonProcessingException e) {
        throw new IllegalStateException(
                "Could not store the reconciled payment failure for "
                        + booking.getReference(),
                e
        );
    }

    log.info(
            "Reconciled {}: no charge exists, seats returned",
            booking.getReference()
    );

    yield true;
}
            // Still nobody's idea what happened. Leave it exactly as it is and ask again next
            // run -- a booking we can't resolve is worth far less than a seat we resell twice.
            case UNKNOWN -> {
                log.warn("Payment for {} is still unresolved; will retry", booking.getReference());
                yield false;
            }
        };
    }

    private void releaseSeats(Booking booking) {
        Flight flight = flightRepository.findByIdForUpdate(booking.getFlight().getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Booking " + booking.getReference() + " references a flight that no longer exists"));
        flight.releaseSeats(booking.getSeatsBooked());
    }
}
