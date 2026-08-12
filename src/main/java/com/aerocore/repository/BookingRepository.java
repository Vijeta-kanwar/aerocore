package com.aerocore.repository;

import com.aerocore.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import org.springframework.data.domain.Pageable;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByReference(String reference);

    boolean existsByReference(String reference);

    List<Booking> findByPassengerEmailIgnoreCaseOrderByBookedAtDesc(String passengerEmail);
    List<Booking> findByUserIdOrderByBookedAtDesc(Long userId);

/**
 * Claims a batch of dead holds for this replica alone.
 *
 * <p>FOR UPDATE locks the rows it returns. SKIP LOCKED is what makes three replicas useful
 * instead of merely safe: without it, replica 2 running this same query would block waiting
 * for rows replica 1 already holds and will never release until it commits. With it,
 * replica 2 steps over them and takes the next hundred. Three replicas, three different
 * batches, no coordination.
 *
 * <p>Native because JPQL has no way to express SKIP LOCKED.
 */
@Query(value = """
        SELECT * FROM bookings
         WHERE status = 'PENDING'
           AND hold_expires_at < :now
         ORDER BY hold_expires_at
         LIMIT :batchSize
         FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
List<Booking> claimExpiredHolds(@Param("now") Instant now, @Param("batchSize") int batchSize);

/**
 * Bookings that reached the gateway and never got an answer.
 *
 * <p>No FOR UPDATE here, unlike the sweeper's claim query. The next step is a network call,
 * and a lock held across that is the mistake this whole design avoids. Two replicas may
 * therefore look up the same reference -- wasteful but harmless, because lookups are reads
 * and the write that follows re-checks the status before acting.
 */
@Query("""
        SELECT b FROM Booking b
         WHERE b.status = com.aerocore.model.BookingStatus.PAYMENT_PENDING
           AND b.bookedAt < :cutoff
         ORDER BY b.bookedAt
        """)
List<Booking> findUnresolvedPayments(@Param("cutoff") Instant cutoff, Pageable pageable);
}