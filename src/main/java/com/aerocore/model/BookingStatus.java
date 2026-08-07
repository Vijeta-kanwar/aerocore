package com.aerocore.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The states a booking can be in, and the only moves allowed between them.
 *
 * <p>These rules live here rather than in BookingService on purpose. Spread across
 * a service they become a pile of if-statements that drift apart as the code grows;
 * gathered here they are one table anyone can read in ten seconds, and adding a
 * rule means editing one line instead of hunting for every caller.
 */
public enum BookingStatus {

    /** Seats are held, payment hasn't settled. The only state carrying a deadline. */
    PENDING,

    /** Paid. The seats belong to the passenger until they cancel. */
    CONFIRMED,

    /** Given up by the passenger. Terminal. */
    CANCELLED,

    /** Reclaimed by the system when the hold ran out. Terminal. */
    EXPIRED;

    private static final Map<BookingStatus, Set<BookingStatus>> ALLOWED_TRANSITIONS;

    static {
        EnumMap<BookingStatus, Set<BookingStatus>> transitions = new EnumMap<>(BookingStatus.class);

        // A hold can be paid for, abandoned by the passenger, or time out.
        transitions.put(PENDING, EnumSet.of(CONFIRMED, CANCELLED, EXPIRED));

        // A paid booking can only be cancelled. It must never expire -- expiry exists
        // to reclaim unpaid holds, and quietly expiring something already paid for
        // would take a seat someone owns.
        transitions.put(CONFIRMED, EnumSet.of(CANCELLED));

        // Nothing comes back from a terminal state. Rebooking means a new booking with
        // a new reference and a fresh seat check against whatever inventory exists now.
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
     * <p>This is the question the old isCancelled() check was really trying to ask.
     * A hold and a paid booking both occupy seats -- what separates them is money,
     * not availability -- and both terminal states have already given theirs back.
     */
    public boolean holdsSeats() {
        return this == PENDING || this == CONFIRMED;
    }

    /**
     * Whether moving to {@code target} should hand seats back to the flight.
     *
     * <p>Derived from {@link #holdsSeats()} instead of being its own list, so the two
     * can't disagree. Add a fifth state tomorrow and you answer "does it hold seats?"
     * once; this follows automatically.
     */
    public boolean releasesSeatsOnTransitionTo(BookingStatus target) {
        return this.holdsSeats() && !target.holdsSeats();
    }
}
