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

## Decision

The key row and the booking commit in a single database transaction. Either both exist or
neither does; a crash anywhere rolls back both, and the retry finds nothing and books
cleanly.

Uniqueness is enforced by a `UNIQUE` constraint on the key rather than by an application
check. Two simultaneous requests both look for the key, both find nothing, and only one of
them can insert it — the database picks the winner, the loser catches the constraint
violation and replays the winner's stored response.

Replays return the response the first request produced, stored verbatim, rather than a
freshly rendered view of the booking. A booking cancelled in between would otherwise make
the same request return two different answers.

A key replayed with a different request body returns **422 Unprocessable Entity**. A network
retry sends the same body; a different body means the client reused a key for different work.
Returning the original booking would leave someone holding a seat on a flight they did not
ask for, and nobody would find out.

## Consequences

The dangerous half-states are gone: there can be no committed key without its booking, and
no committed booking without its key.

The cost is that idempotency is now part of the booking transaction rather than a layer
sitting beside it, and that the request body has to be hashed and stored so a mismatch can be
detected at all.

Returning 422 rather than replaying makes a client bug visible immediately instead of
producing a confusing success. The rule of thumb it follows: fail quietly on network errors,
loudly on programmer errors.

Two things are deliberately unfinished. The `idempotency_keys` table grows without bound —
nothing prunes it, and a retry arriving a week later is a new intent rather than a duplicate,
so keys should eventually expire. And a request whose first attempt is still in flight gets
409 "still being processed" rather than waiting for it; telling the caller to retry with the
same key is honest, but a client that retries immediately will simply get 409 again.
