package com.aerocore.model;

/**
 * Where a recorded request got to.
 *
 * <p>IN_PROGRESS is the only non-terminal state. COMPLETED means the request
 * succeeded, while FAILED means the payment was definitely declined. FAILED
 * must not be used for an unresolved or unknown payment outcome.
 */
public enum IdempotencyStatus {

    /** The request started but its final payment outcome is not known yet. */
    IN_PROGRESS,

    /** The request succeeded and its response is stored for replay. */
    COMPLETED,

    /** The payment was definitely declined and the failure is stored for replay. */
    FAILED
}