package com.aerocore.dto;

import com.aerocore.model.Flight;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalTime;

public record FlightResponse(
        Long id,
        String flightNumber,
        String airline,
        String origin,
        String destination,
        @JsonFormat(pattern = "HH:mm") LocalTime departureTime,
        @JsonFormat(pattern = "HH:mm") LocalTime arrivalTime,
        BigDecimal price,
        int totalSeats,
        int availableSeats
) {
    public static FlightResponse from(Flight flight) {
        return new FlightResponse(
                flight.getId(),
                flight.getFlightNumber(),
                flight.getAirline(),
                flight.getOrigin(),
                flight.getDestination(),
                flight.getDepartureTime(),
                flight.getArrivalTime(),
                flight.getPrice(),
                flight.getTotalSeats(),
                flight.getAvailableSeats()
        );
    }
}
