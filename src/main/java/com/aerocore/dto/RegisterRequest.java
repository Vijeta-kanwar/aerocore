// ===== RegisterRequest.java =====
package com.aerocore.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 160) String email,

        // Length is the cheapest real protection there is, and the only rule that survives
        // contact with users. Complexity requirements mostly produce Password1!.
        @NotBlank @Size(min = 10, max = 72) String password,

        @NotBlank @Size(min = 2, max = 120) String fullName) { }
