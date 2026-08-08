package com.aerocore;

import com.aerocore.model.Booking;
import com.aerocore.model.Flight;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalTime;

/** Builders for test data. Ids are set reflectively because they are database-assigned. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static Flight flight(Long id, int totalSeats, int availableSeats) {
        Flight flight = new Flight("AI101", "Air India", "Delhi", "Mumbai",
                LocalTime.of(6, 0), LocalTime.of(8, 15),
                new BigDecimal("5499.00"), totalSeats);
        ReflectionTestUtils.setField(flight, "id", id);
        ReflectionTestUtils.setField(flight, "availableSeats", availableSeats);
        return flight;
    }

    public static Flight flight(Long id) {
        return flight(id, 180, 180);
    }

    public static Booking booking(Long id, Flight flight, int seats) {
        BigDecimal total = flight.getPrice().multiply(BigDecimal.valueOf(seats));
        Booking booking = new Booking("AT-7F3K2Q", flight, "Vijeta Kanwar",
                "vijeta@example.com", "9876543210", seats, total);
        ReflectionTestUtils.setField(booking, "id", id);
        return booking;
    }
}
