package com.aerocore.dto;

public record AuthResponse(
        String token,
        long expiresInSeconds,
        String email,
        String role) { }
