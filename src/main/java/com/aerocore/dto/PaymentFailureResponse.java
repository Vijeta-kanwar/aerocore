package com.aerocore.dto;

public record PaymentFailureResponse(
        String reference,
        String message
) {
}