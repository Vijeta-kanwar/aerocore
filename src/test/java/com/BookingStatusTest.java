package com.aerocore.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transition table written a second time, from the outside. If someone widens a
 * rule in the enum without meaning to, one of these goes red -- which is the entire
 * reason for writing the table twice.
 */
class BookingStatusTest {

    @ParameterizedTest(name = "{0} -> {1} is allowed")
    @CsvSource({
            "PENDING,   CONFIRMED",
            "PENDING,   CANCELLED",
            "PENDING,   EXPIRED",
            "CONFIRMED, CANCELLED"
    })
    void allowsTheLegalMoves(BookingStatus from, BookingStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest(name = "{0} -> {1} is rejected")
    @CsvSource({
            // A paid booking must never be swept up by the expiry job.
            "CONFIRMED, EXPIRED",
            // Cancelling twice would credit the seats twice.
            "CANCELLED, CANCELLED",
            // Nothing returns from a terminal state.
            "CANCELLED, CONFIRMED",
            "EXPIRED,   CONFIRMED",
            "EXPIRED,   CANCELLED",
            // Going back to an unpaid hold after paying makes no sense.
            "CONFIRMED, PENDING"
    })
    void rejectsTheIllegalMoves(BookingStatus from, BookingStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(BookingStatus.class)
    void neverAllowsAStateToTransitionToItself(BookingStatus status) {
        assertThat(status.canTransitionTo(status)).isFalse();
    }

    @Test
    @DisplayName("only PENDING and CONFIRMED take seats out of inventory")
    void identifiesTheStatesThatOccupySeats() {
        assertThat(BookingStatus.PENDING.holdsSeats()).isTrue();
        assertThat(BookingStatus.CONFIRMED.holdsSeats()).isTrue();
        assertThat(BookingStatus.CANCELLED.holdsSeats()).isFalse();
        assertThat(BookingStatus.EXPIRED.holdsSeats()).isFalse();
    }

    @Test
    @DisplayName("paying for a hold must not hand the seats back")
    void doesNotReleaseSeatsWhenAHoldIsPaidFor() {
        assertThat(BookingStatus.PENDING.releasesSeatsOnTransitionTo(BookingStatus.CONFIRMED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"CANCELLED", "EXPIRED"})
    void releasesSeatsWhenABookingEnds(BookingStatus target) {
        assertThat(BookingStatus.PENDING.releasesSeatsOnTransitionTo(target)).isTrue();
        assertThat(BookingStatus.CONFIRMED.releasesSeatsOnTransitionTo(target)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"CANCELLED", "EXPIRED"})
    void treatsEndStatesAsTerminal(BookingStatus status) {
        assertThat(status.isTerminal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = BookingStatus.class, names = {"PENDING", "CONFIRMED"})
    void treatsLiveStatesAsNonTerminal(BookingStatus status) {
        assertThat(status.isTerminal()).isFalse();
    }
}
