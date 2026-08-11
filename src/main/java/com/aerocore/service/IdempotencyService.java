package com.aerocore.service;

import com.aerocore.dto.BookingRequest;
import com.aerocore.model.Booking;
import com.aerocore.model.IdempotencyRecord;
import com.aerocore.repository.IdempotencyRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional bookends of an idempotent checkout.
 *
 * <p>Kept apart from {@link BookingCheckoutService} for two reasons. A constraint violation
 * poisons its transaction, so the recovery read has to happen outside it. And Spring's
 * @Transactional proxy only intercepts calls arriving from outside the bean -- a method
 * calling its own transactional neighbour silently gets no transaction at all.
 *
 * <p>The split into begin/complete is what payment forced. When checkout was one transaction,
 * one method could open the record and close it. Now there is a gateway call in between, so
 * the record is opened, committed as IN_PROGRESS, and closed later.
 */
@Service
public class IdempotencyService {

    private final BookingService bookingService;
    private final IdempotencyRecordRepository recordRepository;

    public IdempotencyService(BookingService bookingService,
                              IdempotencyRecordRepository recordRepository) {
        this.bookingService = bookingService;
        this.recordRepository = recordRepository;
    }

    /**
     * Records the key and reserves the seat, both or neither.
     *
     * <p>Writing the key first means a crash anywhere after it rolls the key back too, so the
     * retry finds nothing and books cleanly. Committing the hold first and the key second
     * would leave a window where the booking exists but nothing remembers it -- and a retry
     * landing in that window books again. One transaction removes the window rather than
     * narrowing it.
     *
     * <p>saveAndFlush pushes the INSERT now, so a duplicate key fails here rather than at
     * commit, before we've done the work of reserving a seat.
     */
    @Transactional
    public Booking beginCheckout(String key, String requestHash, BookingRequest request) {
        recordRepository.saveAndFlush(new IdempotencyRecord(key, requestHash));
        return bookingService.createHold(request);
    }

    @Transactional
    public Booking createHold(BookingRequest request) {
        return bookingService.createHold(request);
    }

    @Transactional
    public void completeCheckout(String key, Long bookingId, String responseBody) {
        IdempotencyRecord record = recordRepository.findByIdempotencyKey(key)
                .orElseThrow(() -> new IllegalStateException("Idempotency record vanished for key " + key));
        record.complete(bookingId, responseBody);
        recordRepository.save(record);
    }
}
