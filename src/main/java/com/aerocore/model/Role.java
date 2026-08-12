package com.aerocore.model;

/**
 * What a user is allowed to do. Two roles is the honest number for this system.
 *
 * <p>Stored as a string rather than an ordinal, for the same reason BookingStatus is:
 * reordering the constants would silently rewrite the meaning of every existing row.
 */
public enum Role {

    /** Books and manages their own bookings. Nothing else. */
    USER,

    /** Also manages the flight schedule. */
    ADMIN;

    /**
     * Spring Security expects authorities to be prefixed. Keeping the conversion here means
     * the prefix appears once, rather than being remembered at every call site.
     */
    public String asAuthority() {
        return "ROLE_" + name();
    }
}
