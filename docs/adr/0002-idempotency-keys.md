# ADR 0002: Commit the idempotency key and the booking in one transaction

## Status

Accepted — 2026-08

## Context

A client may send the same booking request twice — a request that timed out, a flaky mobile
connection retrying, a user double-tapping Book. Without protection the second request
creates a second booking and reserves seats again.

Nothing in the seat logic catches this, and that is the point worth being clear about. The
conditional update was not wrong: it correctly reserved two seats because it correctly
received two requests. The database cannot tell a retry from a genuine second booking,
because they are identical. Only the client knows, so the client sends a key and the server
remembers it.

The real question is not whether to store a key but whether the key and the booking may be
committed separately. Both orderings fail:

- **Key first, booking second.** A crash in between leaves a key with no booking. The retry
  finds the key, assumes the work was done, and returns a booking that does not exist. The
  user is stuck: they have no booking and no way to make one with that key.

- **Booking first, key second.** A crash in between leaves a booking nothing remembers. The
  retry finds no key, treats the request as new, and books a second seat — the exact failure
  the key was meant to prevent.

Payment adds another problem: a payment attempt does not always have a definite outcome.
A gateway may return a definite decline, a success, or an unknown/unresolved result such as
a timeout. Treating every payment failure as terminal could cause seats to be released
even when the payment may actually have succeeded.

## Decision

The key row and the booking commit are kept in a single database transaction. Either both
exist or neither does; a crash anywhere rolls back both, and the retry finds nothing and
books cleanly.

Uniqueness is enforced by a `UNIQUE` constraint on the key rather than by an application
check. Two simultaneous requests both look for the key, both find nothing, and only one of
them can insert it — the database picks the winner, the loser catches the constraint
violation and re-reads the winner's record to replay its result.

Replays return the response the first request produced, stored verbatim, rather than a
freshly rendered view of the booking. A booking cancelled in between would otherwise make
the same request return two different answers.

The idempotency record has three states:

- **`IN_PROGRESS`** — the request has started but has not reached a terminal outcome.
- **`COMPLETED`** — the payment and booking completed successfully and the response was
  stored.
- **`FAILED`** — the payment received a definite decline. Seats are released, the booking
  is cancelled, and the failure response is stored.

A definite payment decline is therefore terminal and is persisted as `FAILED`. A retry with
the same idempotency key replays the same stored failure rather than attempting payment
again.

An unknown payment outcome is deliberately different. If the gateway cannot confirm
whether the payment succeeded, the request remains `IN_PROGRESS`. Seats are not released
and the idempotency record is not marked `FAILED`, because doing so could release seats
after money may already have moved. The unresolved payment is left for reconciliation to
determine the final outcome.

A key replayed with a different request body returns **422 Unprocessable Entity**. A network
retry sends the same body; a different body means the client reused a key for different work.

Returning the original booking would leave someone holding a seat on a flight they did not
ask for, and nobody would find out.

A request whose idempotency record is still `IN_PROGRESS` returns **409 Conflict** rather
than waiting for the original request to finish. The caller should retry with the same key.

## Consequences

The dangerous half-states are gone: there can be no committed key without its booking, and
no committed booking without its key.

The cost is that idempotency is now part of the booking transaction rather than a layer
sitting beside it, and that the request body has to be hashed and stored so a mismatch can
be detected at all.

Returning 422 rather than replaying makes a client bug visible immediately instead of
producing a confusing success. The rule of thumb it follows: fail quietly on network
errors, loudly on programmer errors.

Persisting definite payment declines as `FAILED` makes the failure itself idempotent:
retries do not charge the customer again and receive the same failure response.

Keeping unknown payment outcomes as `IN_PROGRESS` avoids incorrectly releasing seats or
declaring a payment failed when the payment provider may have accepted the charge. The
trade-off is that these records require reconciliation before they can reach a terminal
state.

The duplicate-key race is handled by the database rather than by a check-then-insert
application race. The losing request re-reads the winner and follows the same replay rules.

Two things remain deliberately unfinished. The `idempotency_keys` table grows without
bound — nothing prunes it, and a retry arriving a week later is a new intent rather than a
duplicate, so keys should eventually expire.

Unknown payment outcomes also require a reconciliation mechanism that can query the payment
provider and move the corresponding `IN_PROGRESS` record to its final state.

The project also has a JaCoCo coverage gate so future changes cannot reduce overall
instruction coverage below 50% or branch coverage below 35%.