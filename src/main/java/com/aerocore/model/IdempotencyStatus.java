package com.aerocore.model;

/**
 * Where a recorded request got to.
 *
 * <p>Only two states, and no transition table -- unlike a booking, this never moves
 * backwards or sideways. IN_PROGRESS becomes COMPLETED, or the transaction rolls back
 * and the row was never there at all.
 */
public enum IdempotencyStatus {

    /**
     * The work started but hasn't committed.
     *
     * <p>Unreachable by a concurrent reader today: the record and the booking commit in one
     * transaction, so nobody else can see this row until it's already COMPLETED. It earns
     * its place once a payment call splits that into two transactions -- then a duplicate
     * really can arrive mid-flight, and this is what it sees.
     */
    IN_PROGRESS,

    /** The work succeeded and the response is stored. A replay returns it verbatim. */
    COMPLETED
}
