# ADR 0005: Lock Only During Payment Reconciliation Settlement

## Status

Accepted — 2026-09

## Context

A payment gateway timeout leaves a booking in `PAYMENT_PENDING` because
the system cannot safely determine whether the customer was charged.

The `PaymentReconciler` periodically finds old `PAYMENT_PENDING`
bookings and asks the gateway for their actual payment outcome.

Multiple application instances may run the reconciler at the same time.
They may therefore discover the same booking and receive the same gateway
result concurrently.

The dangerous operation is not discovery. It is settlement: changing the
booking state and, for a failed payment, releasing its seats.

If two workers both settle the same declined payment, both could attempt
to release the seats. That would corrupt flight inventory.

The system therefore needs concurrency protection around settlement
without holding database locks while making an external gateway call.

## Decision

Do not lock rows during candidate discovery.

`findUnresolvedPayments()` only identifies bookings that may need
reconciliation. It returns `PAYMENT_PENDING` bookings older than the
configured grace period. This query is deliberately unlocked.

The gateway lookup also happens outside the database transaction.

When an outcome is ready to apply, `PaymentReconciliationService` starts
a short transaction and locks the booking row using
`SELECT ... FOR UPDATE` through `findBookingByIdForUpdate()`.

The service then re-checks that the booking is still
`PAYMENT_PENDING`.

The settlement sequence is:

1. Discover a candidate without taking a lock.
2. Query the payment gateway outside a database transaction.
3. Start the settlement transaction.
4. Lock the booking row.
5. Re-check the booking state.
6. Apply the outcome only if the booking is still `PAYMENT_PENDING`.
7. For a successful payment, confirm the booking and complete its
   idempotency record.
8. For `DECLINED` or `NOT_FOUND`, lock the flight row, release the held
   seats, cancel the booking, and mark the idempotency record as `FAILED`.
9. Commit the settlement atomically.

An `UNKNOWN` outcome makes no state change and remains eligible for a
later reconciliation attempt.

## Why Discovery Does Not Lock

Candidate discovery is only a hint that a booking might need work.

Several reconciler workers are allowed to discover the same booking.
Preventing duplicate discovery is unnecessary because discovery does not
change state.

Taking a lock during discovery would also be counterproductive: the
worker would still need to call the payment gateway, and the system must
never hold a database transaction open while waiting for an external
service.

Correctness therefore does not depend on which worker discovers the
booking first.

## Why Settlement Does Lock

Settlement is the critical section.

Consider two reconciler workers processing the same declined payment:

    Worker A                         Worker B
        |                                |
        | discover booking               | discover booking
        |                                |
        | gateway -> DECLINED            | gateway -> DECLINED
        |                                |
        | SELECT FOR UPDATE              |
        |------------------------------->|
        | lock acquired                  | waits
        |                                |
        | release seats                  |
        | cancel booking                 |
        | mark idempotency FAILED        |
        | commit                         |
        |                                |
        |                                | lock acquired
        |                                | sees CANCELLED
        |                                | returns false

The second worker therefore cannot repeat the settlement after the
first worker commits.

The state check after acquiring the lock is essential. Checking the
state before acquiring the lock would still leave a race between the
check and the update.

## Transaction Boundary

The gateway lookup is intentionally outside the settlement transaction.

The transaction begins only when the system is ready to apply the known
gateway result. This keeps the transaction short and allows the booking
and idempotency changes to commit atomically.

For failed outcomes, the booking lock and flight lock are held only for
the database work required to release the seats and persist the final
state.

## Consequences

### Positive

- Multiple reconciler workers can safely process the same candidate.
- Seat release happens at most once for a booking.
- A stale reconciliation result cannot overwrite a booking that has
  already reached another state.
- The gateway is never called while holding a database transaction.
- Booking settlement and idempotency state change atomically.

### Negative

- The same booking may be discovered by multiple workers.
- Correctness depends on the settlement lock and post-lock state check.
- A booking returning `UNKNOWN` remains `PAYMENT_PENDING` and can be
  retried indefinitely under the current reconciliation policy.
- Failed settlement requires locking both the booking and its flight,
  introducing short-lived database contention.

## Testing

The reconciliation behavior is covered by unit tests for successful,
declined, not-found, unknown, missing-booking, and already-resolved
outcomes.

Concurrency is additionally tested against real PostgreSQL using
Testcontainers. Two concurrent reconciliation attempts against the same
`PAYMENT_PENDING` booking result in exactly one successful settlement,
with the final seat inventory showing that the held seats were released
only once.
