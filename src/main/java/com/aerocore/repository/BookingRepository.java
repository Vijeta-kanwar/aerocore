package com.aerocore.repository;

import com.aerocore.model.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Optional<Booking> findByReference(String reference);

    boolean existsByReference(String reference);

    List<Booking> findByPassengerEmailIgnoreCaseOrderByBookedAtDesc(String passengerEmail);
}
