package com.aerocore.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalTime;

public record FlightRequest(

        @NotBlank(message = "flightNumber is required")
        @Pattern(regexp = "^[A-Z0-9]{2,10}$", message = "flightNumber must be 2-10 uppercase letters or digits")
        String flightNumber,

        @NotBlank(message = "airline is required")
        @Size(max = 60)
        String airline,

        @NotBlank(message = "origin is required")
        @Size(max = 60)
        String origin,

        @NotBlank(message = "destination is required")
        @Size(max = 60)
        String destination,

        @NotNull(message = "departureTime is required, format HH:mm")
        @JsonFormat(pattern = "HH:mm")
        LocalTime departureTime,

        @NotNull(message = "arrivalTime is required, format HH:mm")
        @JsonFormat(pattern = "HH:mm")
        LocalTime arrivalTime,

        @NotNull(message = "price is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "price must be greater than 0")
        BigDecimal price,

        @Min(value = 1, message = "totalSeats must be at least 1")
        int totalSeats
) {
}
