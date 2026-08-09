package com.aerocore.service;

import com.aerocore.dto.BookingRequest;
import com.aerocore.dto.BookingResponse;
import com.aerocore.model.Booking;
import com.aerocore.model.IdempotencyRecord;
import com.aerocore.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of idempotent booking. Kept apart from
 * {@link IdempotentBookingService} so that a constraint violation can be caught by a caller
 * that isn't inside the poisoned transaction.
 */
@Service
public class IdempotencyService {

    private final BookingService bookingService;
    private final IdempotencyRecordRepository recordRepository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(BookingService bookingService,
                              IdempotencyRecordRepository recordRepository,
                              ObjectMapper objectMapper) {
        this.bookingService = bookingService;
        this.recordRepository = recordRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Books a seat and records the key, both or neither.
     *
     * <p>The ordering matters more than it looks. Writing the key first means a crash
     * anywhere after it rolls the key back too, so the retry finds nothing and books cleanly.
     * Committing the booking first and the key second would leave a window where the booking
     * exists but nothing remembers it -- and a retry landing in that window books again. One
     * transaction removes the window instead of narrowing it.
     *
     * <p>saveAndFlush, not save: it pushes the INSERT to the database now, so a duplicate key
     * fails here rather than at commit, before we've done the work of booking a seat.
     */
    @Transactional
    public BookingResponse createRecordingKey(String key, String requestHash, BookingRequest request) {
        IdempotencyRecord record = recordRepository.saveAndFlush(new IdempotencyRecord(key, requestHash));

        Booking booking = bookingService.create(request);
        BookingResponse response = BookingResponse.from(booking);

        record.complete(booking.getId(), serialize(response));
        recordRepository.save(record);
        return response;
    }

    @Transactional
    public BookingResponse createWithoutIdempotency(BookingRequest request) {
        return BookingResponse.from(bookingService.create(request));
    }

    private String serialize(BookingResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store the booking response", e);
        }
    }
}
