package com.aerocore.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Email;

// No @Size on the password here. Rejecting a short password at login would tell an attacker
// something about the rules rather than about this attempt, and every failure should look
// identical from outside.
public record LoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password) { }