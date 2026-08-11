package com.aerocore.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The states a booking can be in, and the only moves allowed between them.
 *
 * <p>These rules live here rather than in a service on purpose. Spread across a service they
 * become a pile of if-statements that drift apart as the code grows; gathered here they are
 * one table anyone can read in ten seconds.
 */
public enum BookingStatus {

    /** Seats held, nothing charged yet. The sweeper's territory. */
    PENDING,

    /**
     * A charge is in flight, or its outcome is unknown.
     *
     * <p>The sweeper never touches this state. That is the entire reason it exists: once
     * money may have moved, "this hold looks abandoned" stops being a safe conclusion.
     */
    PAYMENT_PENDING,

    /** Paid. The seats belong to the passenger until they cancel. */
    CONFIRMED,

    /** Given up by the passenger, or released after a declined payment. Terminal. */
    CANCELLED,

    /** Reclaimed by the sweeper because an unpaid hold ran out. Terminal. */
    EXPIRED;

    private static final Map<BookingStatus, Set<BookingStatus>> ALLOWED_TRANSITIONS;

    static {
        EnumMap<BookingStatus, Set<BookingStatus>> transitions = new EnumMap<>(BookingStatus.class);

        // A fresh hold either heads for the gateway, is abandoned, or times out.
        transitions.put(PENDING, EnumSet.of(PAYMENT_PENDING, CANCELLED, EXPIRED));

        // Once the gateway has been called there are only two honest outcomes: it worked, or
        // it definitively didn't and the seats go back. Notably absent is EXPIRED -- nothing
        // may quietly time out a booking that might have been paid for.
        transitions.put(PAYMENT_PENDING, EnumSet.of(CONFIRMED, CANCELLED));

        transitions.put(CONFIRMED, EnumSet.of(CANCELLED));

        // Nothing comes back from a terminal state. Rebooking means a new booking with a new
        // reference and a fresh seat check against whatever inventory exists now.
        transitions.put(CANCELLED, EnumSet.noneOf(BookingStatus.class));
        transitions.put(EXPIRED, EnumSet.noneOf(BookingStatus.class));

        ALLOWED_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    public boolean canTransitionTo(BookingStatus target) {
        return target != null && ALLOWED_TRANSITIONS.get(this).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.get(this).isEmpty();
    }

    /**
     * Whether a booking in this state is currently occupying seats in inventory.
     *
     * <p>A hold, a hold being paid for, and a paid booking all occupy seats -- what separates
     * them is money, not availability -- and both terminal states have given theirs back.
     */
    public boolean holdsSeats() {
        return this == PENDING || this == PAYMENT_PENDING || this == CONFIRMED;
    }

    /** Whether this state still needs a deadline attached. Mirrors ck_bookings_hold_expiry. */
    public boolean carriesDeadline() {
        return this == PENDING || this == PAYMENT_PENDING;
    }

    /**
     * Whether moving to {@code target} should hand seats back to the flight.
     *
     * <p>Derived from {@link #holdsSeats()} instead of being its own list, so the two can't
     * disagree. Adding PAYMENT_PENDING today needed one line, not two.
     */
    public boolean releasesSeatsOnTransitionTo(BookingStatus target) {
        return this.holdsSeats() && !target.holdsSeats();
    }
}
