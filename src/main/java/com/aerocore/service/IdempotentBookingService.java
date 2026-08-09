package com.aerocore.service;

import com.aerocore.dto.BookingRequest;
import com.aerocore.dto.BookingResponse;
import com.aerocore.exception.IdempotencyKeyReusedException;
import com.aerocore.exception.RequestInProgressException;
import com.aerocore.model.IdempotencyRecord;
import com.aerocore.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Turns "create a booking" into "create a booking at most once, no matter how many times
 * the client asks".
 *
 * <p>Note what this class is not: transactional. That is the whole trick. Inserting a
 * duplicate key raises a constraint violation, and a transaction that has seen one is
 * poisoned -- it can only roll back. So the recovery read has to happen outside it, which
 * means the transactional work lives in a separate bean ({@link IdempotencyService}) and
 * this class calls it. Keeping both halves in one class would have quietly done nothing:
 * Spring's proxy only intercepts calls arriving from outside the bean, so a method calling
 * its own @Transactional neighbour gets no transaction at all.
 */
@Service
public class IdempotentBookingService {

    private final IdempotencyService idempotencyService;
    private final IdempotencyRecordRepository recordRepository;
    private final ObjectMapper objectMapper;

    public IdempotentBookingService(IdempotencyService idempotencyService,
                                    IdempotencyRecordRepository recordRepository,
                                    ObjectMapper objectMapper) {
        this.idempotencyService = idempotencyService;
        this.recordRepository = recordRepository;
        this.objectMapper = objectMapper;
    }

    public BookingResponse create(String idempotencyKey, BookingRequest request) {
        // No key means no protection. The endpoint stays usable for clients that don't send
        // one, and the README is honest that those clients can double-book themselves.
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return idempotencyService.createWithoutIdempotency(request);
        }

        String hash = hash(request);

        // The common replay: the first request finished long ago.
        Optional<IdempotencyRecord> existing = recordRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), hash, idempotencyKey);
        }

        try {
            return idempotencyService.createRecordingKey(idempotencyKey, hash, request);
        } catch (DataIntegrityViolationException duplicate) {
            // Lost a race we couldn't see coming: both requests checked above and both found
            // nothing, because neither had committed yet. The unique index picked a winner and
            // handed us this. By the time it surfaces the winner has committed, so its record
            // is readable now.
            IdempotencyRecord winner = recordRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> duplicate);
            return replay(winner, hash, idempotencyKey);
        }
    }

    private BookingResponse replay(IdempotencyRecord record, String hash, String key) {
        if (!record.matches(hash)) {
            throw new IdempotencyKeyReusedException(key);
        }
        if (!record.isCompleted()) {
            throw new RequestInProgressException(key);
        }
        return deserialize(record.getResponseBody());
    }

    /**
     * SHA-256 over the serialized request.
     *
     * <p>Jackson writes a record's fields in declaration order, so the same request always
     * produces the same bytes. That is enough here because the hash only ever compares two
     * bodies this service serialized itself -- it is not a signature over what arrived on
     * the wire, and shouldn't be treated as one.
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

    private BookingResponse deserialize(String json) {
        try {
            return objectMapper.readValue(json, BookingResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored idempotent response could not be read", e);
        }
    }
}
