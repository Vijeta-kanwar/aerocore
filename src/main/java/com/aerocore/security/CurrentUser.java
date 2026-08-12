package com.aerocore.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Answers "who is calling" without every service having to know about Spring Security.
 *
 * <p>The filter put the user id in the security context; this reads it back. One place that
 * touches SecurityContextHolder means one place to change if authentication is ever done
 * differently, and services that stay readable.
 */
public final class CurrentUser {

    private CurrentUser() { }

    public static Long id() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            // Reaching here means an authenticated endpoint let an anonymous request through,
            // which is a configuration bug rather than a user error. Fail loudly.
            throw new IllegalStateException("No authenticated user in the security context");
        }
        return userId;
    }

    public static boolean isAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(granted -> "ROLE_ADMIN".equals(granted.getAuthority()));
    }
}
