package com.aerocore.repository;

import com.aerocore.model.Flight;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FlightRepository extends JpaRepository<Flight, Long> {

    Optional<Flight> findByFlightNumber(String flightNumber);

    boolean existsByFlightNumber(String flightNumber);

    @Query("""
            SELECT f FROM Flight f
            WHERE LOWER(f.origin) = LOWER(:origin)
              AND LOWER(f.destination) = LOWER(:destination)
            ORDER BY f.departureTime
            """)
    List<Flight> search(@Param("origin") String origin, @Param("destination") String destination);

    /**
     * Decrements seats only if enough remain, and reports whether it happened.
     *
     * <p>The safety here comes from the WHERE clause, not from a lock we asked for.
     * Postgres locks the row as part of writing it, and when a second transaction
     * reaches the same row it blocks, then re-evaluates this WHERE against the value
     * the winner committed. So the loser's condition is checked against fresh data and
     * simply fails to match. There is no window between reading the count and acting
     * on it, because reading and acting are the same statement.
     *
     * <p>Returns the number of rows changed: 1 on success, 0 if the flight is full OR
     * doesn't exist. Zero is ambiguous, which is why the caller establishes existence
     * separately before getting here.
     *
     * <p>version is bumped by hand. A bulk update bypasses Hibernate's entity tracking,
     * so @Version would otherwise go stale and stop protecting the entity-based update
     * paths that still rely on it.
     *
     * <p>clearAutomatically evicts the persistence context afterwards. Without it, any
     * Flight already loaded in this transaction keeps its old seat count in memory and
     * quietly reports a number the database no longer agrees with.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Flight f
               SET f.availableSeats = f.availableSeats - :seats,
                   f.version = f.version + 1
             WHERE f.id = :id
               AND f.availableSeats >= :seats
            """)
    int reserveSeats(@Param("id") Long id, @Param("seats") int seats);

    /**
     * Takes a row-level write lock (SELECT ... FOR UPDATE) before a seat count is read.
     *
     * <p>Still used by the cancel and delete paths, which read a booking, decide from it,
     * and then write — the read-decide-write shape that genuinely needs a lock held across
     * all three steps. The booking path no longer needs it because its decision moved into
     * the WHERE clause of reserveSeats above.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Flight f WHERE f.id = :id")
    Optional<Flight> findByIdForUpdate(@Param("id") Long id);
}
