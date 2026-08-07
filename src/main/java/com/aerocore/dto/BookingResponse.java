package com.aerocore.dto;

import com.aerocore.model.Booking;

import java.math.BigDecimal;
import java.time.Instant;

public record BookingResponse(
        Long id,
        String reference,
        Long flightId,
        String flightNumber,
        String airline,
        String origin,
        String destination,
        String passengerName,
        String passengerEmail,
        String passengerPhone,
        int seatsBooked,
        BigDecimal totalAmount,
        String status,
        Instant bookedAt
) {
    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getReference(),
                booking.getFlight().getId(),
                booking.getFlight().getFlightNumber(),
                booking.getFlight().getAirline(),
                booking.getFlight().getOrigin(),
                booking.getFlight().getDestination(),
                booking.getPassengerName(),
                booking.getPassengerEmail(),
                booking.getPassengerPhone(),
                booking.getSeatsBooked(),
                booking.getTotalAmount(),
                booking.getStatus().name(),
                booking.getBookedAt()
        );
    }
}
