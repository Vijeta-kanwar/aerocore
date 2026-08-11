package com.aerocore.payment;

import java.math.BigDecimal;

/**
 * What we need from a payment provider, and nothing more.
 *
 * <p>An interface rather than a direct call to some SDK, because the interesting behaviour
 * to design against is failure: slow responses, timeouts, declines, and outcomes we never
 * learn. A real provider gives you those on their schedule; a stub gives them to you on
 * demand, which is the only way to actually test the transaction boundaries.
 */
public interface PaymentGateway {

    /**
     * Charges a card, keyed by the booking reference.
     *
     * <p>The reference is the idempotency key -- the same idea built on Day 3, from the other
     * side of the wire. If this call times out and we retry, the gateway must recognise the
     * reference and return the original charge rather than taking the money twice. We are now
     * the client depending on someone else's guarantee.
     */
    PaymentResult charge(String bookingReference, BigDecimal amount);

    /**
     * Asks what happened to a charge we may or may not have completed.
     *
     * <p>Without this, a booking stuck in PAYMENT_PENDING after a crash is unresolvable: there
     * would be no way to find out whether the passenger paid. Any gateway that cannot answer
     * this question cannot be used safely.
     */
    PaymentResult lookup(String bookingReference);

    record PaymentResult(Outcome outcome, String chargeId, String message) {

        public enum Outcome {
            /** Money moved. chargeId is populated. */
            SUCCEEDED,
            /** The gateway said no, definitively. Safe to release the seats. */
            DECLINED,
            /** No charge exists for this reference. Also safe to release. */
            NOT_FOUND,
            /**
             * We don't know. A timeout, a connection reset, a 500.
             *
             * <p>The one outcome that must never release seats: "I didn't hear back" and
             * "it didn't happen" are different claims, and only one of them is safe to act on.
             */
            UNKNOWN
        }

        public static PaymentResult succeeded(String chargeId) {
            return new PaymentResult(Outcome.SUCCEEDED, chargeId, "Payment accepted");
        }

        public static PaymentResult declined(String message) {
            return new PaymentResult(Outcome.DECLINED, null, message);
        }

        public static PaymentResult notFound() {
            return new PaymentResult(Outcome.NOT_FOUND, null, "No charge exists for this reference");
        }

        public static PaymentResult unknown(String message) {
            return new PaymentResult(Outcome.UNKNOWN, null, message);
        }

        public boolean isSucceeded() {
            return outcome == Outcome.SUCCEEDED;
        }

        public boolean isUnknown() {
            return outcome == Outcome.UNKNOWN;
        }
    }
}
