package com.aerocore.repository;

import com.aerocore.model.Flight;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     * Takes a row-level write lock (SELECT ... FOR UPDATE) before a seat count is read.
     * Without this, two pods handling concurrent bookings can both read "2 seats left"
     * and both succeed, overselling the flight. With three replicas this is not theoretical.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Flight f WHERE f.id = :id")
    Optional<Flight> findByIdForUpdate(@Param("id") Long id);
}
