package com.aerocore.service;

import com.aerocore.dto.BookingRequest;
import com.aerocore.dto.BookingResponse;
import com.aerocore.exception.IdempotencyKeyReusedException;
import com.aerocore.exception.PaymentFailedException;
import com.aerocore.exception.RequestInProgressException;
import com.aerocore.model.Booking;
import com.aerocore.model.IdempotencyRecord;
import com.aerocore.payment.PaymentGateway;
import com.aerocore.payment.PaymentGateway.PaymentResult;
import com.aerocore.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Checkout, end to end: hold a seat, take the money, confirm.
 *
 * <p>Deliberately not transactional. The gateway call in the middle takes seconds, and a
 * transaction spanning it would hold a database connection and a flight row lock for the
 * whole wait. Ten connections in the pool and four seconds a call is roughly two bookings a
 * second for the entire application -- and when the pool empties, the health endpoint can't
 * get a connection either, so a slow payment provider takes down endpoints that have nothing
 * to do with payments.
 *
 * <p>Worse, a rollback would not undo a charge. The database would forget the booking while
 * the customer's money stayed gone, and nothing in the system would know.
 *
 * <p>So the seat is protected by a row saying PENDING rather than by a lock. A lock protects
 * for milliseconds; a state protects for minutes, survives a restart, and costs nothing to
 * hold.
 */
@Service
public class BookingCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(BookingCheckoutService.class);

    private final IdempotencyService idempotencyService;
    private final BookingPaymentService paymentService;
    private final PaymentGateway paymentGateway;
    private final IdempotencyRecordRepository recordRepository;
    private final ObjectMapper objectMapper;

    public BookingCheckoutService(IdempotencyService idempotencyService,
                                  BookingPaymentService paymentService,
                                  PaymentGateway paymentGateway,
                                  IdempotencyRecordRepository recordRepository,
                                  ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.paymentService = paymentService;
        this.paymentGateway = paymentGateway;
        this.recordRepository = recordRepository;
        this.objectMapper = objectMapper;
    }

    public BookingResponse checkout(String idempotencyKey, BookingRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // No key, no protection. The endpoint stays usable for clients that don't send one,
            // and the README is honest that those clients can double-book themselves.
            return runCheckout(idempotencyService.createHold(request));
        }

        String hash = hash(request);

        Optional<IdempotencyRecord> existing = recordRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash, idempotencyKey);
        }

        Booking hold;
        try {
            // One transaction: the key row and the seat reservation commit together, so a crash
            // rolls back both and a retry books cleanly.
            hold = idempotencyService.beginCheckout(idempotencyKey, hash, request);
        } catch (DataIntegrityViolationException duplicate) {
            // Lost a race neither request could see: both checked above, neither had committed.
            // The unique index picked a winner and handed us this.
            IdempotencyRecord winner = recordRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> duplicate);
            return replay(winner, hash, idempotencyKey);
        }

        BookingResponse response = runCheckout(hold);
        idempotencyService.completeCheckout(idempotencyKey, hold.getId(), serialize(response));
        return response;
    }

    /**
     * The three phases, with the transaction boundaries falling between them.
     */
    private BookingResponse runCheckout(Booking hold) {
        // Committed before the gateway is touched, so the sweeper can no longer reach this row.
        paymentService.beginPayment(hold.getId());

        // No transaction is open across this call. Nothing is locked, no connection is held.
        PaymentResult result = paymentGateway.charge(hold.getReference(), hold.getTotalAmount());

        if (result.isSucceeded()) {
    return paymentService.confirmPaid(hold.getId(), result.chargeId());
}

        if (result.isUnknown()) {
            // The seats stay held. We do not know whether money moved, and releasing a seat that
            // was paid for is worse than holding one that wasn't. The reconciler resolves this
            // later by asking the gateway what actually happened.
            log.warn("Payment outcome unknown for {}; leaving it for reconciliation", hold.getReference());
            throw new PaymentFailedException(hold.getReference(),
                    "We could not confirm your payment. Check your bookings shortly before trying again.");
        }

        // A definite decline is the only case where releasing the seats is safe.
        paymentService.releaseAfterFailedPayment(hold.getId());
        throw new PaymentFailedException(hold.getReference(), result.message());
    }

    private BookingResponse replay(IdempotencyRecord record, String hash, String key) {
        if (!record.matches(hash)) {
            throw new IdempotencyKeyReusedException(key);
        }
        if (!record.isCompleted()) {
            // Now genuinely reachable. Checkout spans three transactions with a gateway call
            // between them, so a duplicate really can arrive while the first is mid-flight.
            throw new RequestInProgressException(key);
        }
        return deserialize(record.getResponseBody());
    }

    /**
     * SHA-256 over the serialized request.
     *
     * <p>Jackson writes a record's fields in declaration order, so the same request always
     * produces the same bytes. That's enough here because the hash only compares two bodies
     * this service serialized itself -- it is not a signature over what arrived on the wire.
     */
    private String hash(BookingRequest request) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(request);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not hash the booking request", e);
        }
    }

    private String serialize(BookingResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store the booking response", e);
        }
    }

    private BookingResponse deserialize(String json) {
        try {
            return objectMapper.readValue(json, BookingResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response could not be read", e);
        }
    }
}
