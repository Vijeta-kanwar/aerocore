package com.aerocore.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BookingRequest(

        @NotNull(message = "flightId is required")
        Long flightId,

        @NotBlank(message = "passengerName is required")
        @Size(min = 2, max = 120, message = "passengerName must be 2-120 characters")
        String passengerName,

        @NotBlank(message = "passengerEmail is required")
        @Email(message = "passengerEmail must be a valid email address")
        String passengerEmail,

        @NotBlank(message = "passengerPhone is required")
        @Pattern(regexp = "^[0-9+\\-\\s]{7,20}$", message = "passengerPhone must be 7-20 digits")
        String passengerPhone,

        @Min(value = 1, message = "seatsBooked must be at least 1")
        @Max(value = 9, message = "seatsBooked cannot exceed 9 per booking")
        int seatsBooked
) {
}
