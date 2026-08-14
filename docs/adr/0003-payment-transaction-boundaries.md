# ADR 0003: Call the payment gateway outside any database transaction

## Status

Accepted — 2026-08

## Context

Wrapping the whole of checkout in one transaction reads well — reserve the seat, charge the
card, confirm the booking, commit — and is badly wrong in three separate ways.

**Contention.** The flight row is locked for the length of the transaction. A gateway that
takes four seconds means every other booking for that flight waits four seconds.

**Connection exhaustion.** A transaction occupies a database connection for its entire life,
and the pool holds ten. Ten connections at four seconds each is roughly 2.5 bookings per
second for the whole application. Once the pool is empty, `/actuator/health` cannot get a
connection either — so readiness fails and Kubernetes pulls the pod. A slow payment provider
takes down endpoints that have nothing to do with payments.

**A rollback cannot undo a charge.** If the gateway succeeds and the database write then
fails, the transaction rolls back: the seat returns, the booking disappears. The money does
not come back. Postgres has no idea a card was charged — that happened over HTTP to another
company — so the customer has paid for a booking that does not exist and nothing in the
system knows.

There is a second decision inside this one. Once a charge may be in flight, "this hold looks
abandoned" stops being a safe conclusion, so the hold sweeper must not touch it. The sweeper
cannot tell the difference on its own, and asking the gateway about every abandoned hold would
mean thousands of calls to hear "never heard of it" — and would tie seat inventory to a third
party's uptime, since a gateway outage would stop the sweeper reclaiming anything.

## Decision

Split checkout into three phases, with the transaction boundaries falling between them:

1. **Transaction:** create the booking as `PENDING` and reserve the seat. Commit.
2. **No transaction:** call the gateway.
3. **Transaction:** confirm the booking, or release the seat. Commit.

The seat is protected by a row saying `PENDING` rather than by a lock. A lock protects for
milliseconds; a state protects for minutes, survives a restart, and occupies no connection.

`PAYMENT_PENDING` is a separate state from `PENDING`, committed before the gateway is called.
The sweeper selects only `PENDING`, so a booking that reached the gateway is invisible to it,
and the transition table forbids `PAYMENT_PENDING → EXPIRED` outright — nothing may quietly
time out a booking that might have been paid for.

An outcome the gateway never reports is treated as unknown, not as failure. The seats stay
held, because "I did not hear back" and "it did not happen" are different claims and only one
makes it safe to resell. A separate reconciler picks these up later: it finds
`PAYMENT_PENDING` bookings past a grace period, asks the gateway what actually happened, and
confirms them or releases the seats. The expensive question gets asked only about the few
bookings that reached the gateway, never about the many that were simply abandoned.

## Consequences

Transactions are short and no connection is held while an external service thinks. In
exchange, payment and database state are no longer one atomic operation, and the code has to
be arranged so that the gap between them is always recoverable.

A crash between the charge and the confirmation leaves the booking in `PAYMENT_PENDING`. That
is not a lost booking — the reconciler resolves it on its next run — but the seat is held
until then, and if the gateway itself is unreachable it stays held indefinitely. That is the
deliberate direction to fail in: a booking stuck for an hour is a smaller problem than a seat
sold twice.

The gateway is a stub, not a real provider. That was a choice, not a shortcut. What this
design needs testing against is latency, declines and outcomes that never arrive, and a real
provider hands those out on its own schedule if at all. A stub gives them on demand — turning
the latency up to four seconds is how the transaction-boundary argument above was verified
rather than assumed.

Two properties are required of any real gateway that replaces it. It must accept an
idempotency key, so a retry after a timeout returns the original charge rather than taking
the money twice — the same mechanism as ADR 0002, with this service on the client side of it.
And it must answer questions about a past charge, or a booking stuck in `PAYMENT_PENDING`
would be unresolvable: there would be no way to find out whether the passenger paid.
