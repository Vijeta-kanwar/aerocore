# AeroCore

A flight-booking backend built around four things that are easy to get subtly wrong: a seat
must not be oversold when two bookings arrive together, one checkout request must not become
two charges, a payment whose outcome is never reported must not strand a seat or take money
for a booking that no longer exists, and an operation on a booking must belong to the person
making it.

The CRUD around flights and bookings is the uninteresting part. What the project is actually
about is transaction boundaries, conditional writes, idempotency, and a state machine that
holds when things fail halfway.

Java 17 · Spring Boot 3.3 · PostgreSQL 16 · Flyway · Docker · Kubernetes · GitHub Actions

---

## The four invariants

### A seat is never oversold

Reservation is a conditional update:

```sql
UPDATE flights
   SET available_seats = available_seats - :n
 WHERE id = :id
   AND available_seats >= :n
```

The affected-row count is the answer. The database decides whether enough seats remained at
the moment of the write; nothing is read into Java and then acted upon, so there is no window
for another booking to change the count underneath.

This is tested against real PostgreSQL under contention. `ConcurrentBookingIT` releases
twenty threads at the last available seat and verifies that exactly one succeeds, nineteen
are rejected, the seat count reaches zero, and exactly one booking row exists.

### One request is one booking

Clients send an `Idempotency-Key`. The key row and the booking commit in the same
transaction, so a crash rolls back both and a retry books cleanly. A replay returns the
original response verbatim; a key reused with a different body is a 422, not a silent replay.

A record has three states rather than two. A definite decline is a finished request, not an
unfinished one, so it settles to `FAILED` and replays that failure. Only an outcome nobody
knows yet stays `IN_PROGRESS`, because that is the one case where a later answer can still
change what the right response was.

### A booking only moves along legal edges

Five states, four legal transitions, all declared in one table on `BookingStatus`.
`PAYMENT_PENDING → EXPIRED` is deliberately not among them: nothing may quietly time out a
booking that might already have been paid for.

### Nothing is claimed that isn't checked

Ownership is enforced in the service layer, the CI smoke test exercises the real stack
against real Postgres, and the concurrency test proves the last-seat invariant under actual
contention. Reconciliation is proven the same way: two settlements racing on one declined
booking return its seats exactly once. Coverage is enforced on `com.aerocore.service`
specifically rather than on the project average, so the floor measures the code the project
is about instead of its DTOs. What is *not* proven is listed under Known limits rather than
left for someone to discover.

---

## How a booking works

Three phases, with the transaction boundaries falling between them:

1. **Reserve** — one transaction: create the booking as `PENDING`, reserve the seats, record
   the idempotency key. Commit.
2. **Pay** — no transaction is open. The gateway is called with the booking reference as its
   idempotency key. This takes seconds; nothing is locked and no connection is held.
3. **Settle** — one transaction: confirm the booking, or release the seats and record the
   failure against the idempotency key. Commit.

The seat is protected during phase 2 by a row that says `PENDING`, not by a lock. A lock
protects for milliseconds; a state protects for minutes, survives a restart, and occupies no
connection.

Just before the gateway is called the booking moves to `PAYMENT_PENDING`, which exists for
one reason: the hold sweeper reclaims abandoned `PENDING` holds, and once money may have
moved, "this hold looks abandoned" stops being a safe conclusion. The sweeper never selects
`PAYMENT_PENDING`. A separate reconciler resolves those by asking the gateway what actually
happened.

Phase 3 is therefore not the only way a booking settles. When the gateway never answers, the
booking stays `PAYMENT_PENDING` holding its seat and the reconciler finishes the job later,
doing exactly what checkout would have done. A charge that had in fact succeeded confirms the
booking and completes the idempotency record — so a client retrying with its original key
eventually receives the booking rather than the error it was handed the first time. A
definite negative, whether declined or no such charge, cancels the booking, returns the
seats, and marks the record `FAILED`. An answer of "still nobody knows" changes nothing at
all, which is the only safe thing to do with it.

---

## Decisions

Recorded in [`docs/adr`](docs/adr/) — one file per decision, each with the context that
forced it and what it cost:

- [0001](docs/adr/0001-conditional-seat-update.md) — reserving seats with a conditional
  update instead of a row lock, and why `cancel()` still uses one
- [0002](docs/adr/0002-idempotency-keys.md) — why the key and the booking share a transaction
- [0003](docs/adr/0003-payment-transaction-boundaries.md) — why the gateway call sits outside
  every transaction
- [0004](docs/adr/0004-stateless-authentication.md) — stateless JWTs across three replicas,
  and what `localStorage` costs
- [0005](docs/adr/0005-payment-reconciliation-locking.md) — why the reconciler's candidate
  query takes no lock while `applyOutcome` does

---

## Bugs worth reading about

**The detached idempotency record.** `reserveSeats` is annotated
`@Modifying(clearAutomatically = true)` so a stale seat count can't be read after a bulk
update. That clears the entire persistence context, not just the `Flight` — including the
`IdempotencyRecord` inserted moments earlier in the same transaction. By the time
`record.complete()` ran, the record was detached, dirty checking never saw the change, and no
UPDATE was issued. Every replay returned "still in progress", forever. Two individually
correct decisions, made a day apart, that broke each other.

**Lazy loading, when one transaction became three.** Mapping a `Booking` to its DTO reads
`flight.getFlightNumber()`, and the association is `LAZY`. That was safe while checkout was a
single transaction, because the mapping happened inside it. Splitting checkout around the
gateway call moved the mapping outside any session, and the proxy had nothing to load from.
Entities belong inside transactions; DTOs travel outside them.

**A CHECK constraint that was right for one transition and wrong for the next.**
`payment_charge_id` was constrained to rows with status `CONFIRMED`. True at the moment of
payment, false the moment a paid booking was cancelled — the constraint fired and every
cancellation of a paid booking became a 500. The charge id records that money moved, and
cancelling doesn't unmake that; it is exactly what a refund needs. A CHECK has to hold for
every state a row will ever be in.

**403 where 401 belonged.** The CI smoke test asserted that an unauthenticated booking is
rejected with 401 and got 403 instead — Spring Security's stateless default, which tells a
client "you're not allowed" when the truth is "I don't know who you are". No unit test could
have caught it, because `@WithMockUser` never exercises the anonymous path. The frontend
depends on the distinction: 401 clears the session and shows sign-in, 403 doesn't.

**A status check that was not the same thing as a lock.** Reconciliation re-read a booking,
checked it was still `PAYMENT_PENDING`, and acted. That reads like the conditional seat
update, but it isn't: the seat update's guard lives inside the `UPDATE` itself, while this was
a read, then an `if`, then a write, with gaps between them. Two replicas could both pass the
check on a declined booking and both return the same seats — an oversell produced by the code
written to settle one. The fix was a pessimistic lock on the booking, taken after the gateway
has already answered so nothing slow happens while it is held.

---

## Known limits

- **No token revocation.** Signing out stops sending the token; it doesn't invalidate it. A
  stolen token works until it expires. The fix is refresh tokens, which need server-side
  state that access tokens deliberately avoid.
- **Idempotency keys are never pruned.** The table grows without bound. A retry arriving a
  week later is a new intent, not a duplicate, so keys should expire.
- **A key that ends in `FAILED` stays failed.** Replays return the stored failure instead of
  attempting payment again, so a passenger who fixes a declined card must book with a new
  idempotency key. That is the deliberate cost of treating one key as one logical request
  with one terminal outcome, rather than as a login for the same seat.
- **The payment gateway is a stub.** Deliberately: what needed testing was latency, declines
  and outcomes that never arrive, and a real provider hands those out on its own schedule.
  A real one must accept an idempotency key and answer questions about past charges.
- **An unknown payment outcome can hold its seat indefinitely.** There is no attempt limit
  and no backoff, so the reconciler asks about the same booking on every run until the
  gateway gives a definite answer, and a gateway that never recovers means a seat held
  forever. That is the chosen direction to fail in — a booking stuck for an hour beats a seat
  sold twice — but the missing pieces are bounded retries, backoff, and an escalation path to
  manual resolution.
- **One Postgres instance.** A stated single point of failure. Production would want an
  operator like CloudNativePG for replication and failover.
- **Demo secrets are committed** so a clean clone runs. Kubernetes Secrets are base64, not
  encryption; production would use Sealed Secrets or an external secrets operator.

---

## Running it

```bash
docker compose up --build
```

Then open <http://localhost:8080>, register an account, and book a flight. Payment happens
server-side inside checkout — there is no separate payment step.

The stub gateway's failure rates are exposed as environment variables in `docker-compose.yml`,
defaulted to zero. Setting `AEROCORE_PAYMENTS_DECLINE_RATE` or
`AEROCORE_PAYMENTS_TIMEOUT_RATE` to `1.0` makes declines and unresolved payments reproducible
on demand, which is how the reconciler is exercised by hand.

### The concurrency proof

```bash
mvn verify -Dtest=ConcurrentBookingIT
```

Testcontainers starts a real PostgreSQL 16 instance, Flyway migrates it, a flight is created
with one remaining seat, and twenty threads are released at the same moment by a
`CountDownLatch`. The test asserts that exactly one booking succeeds, nineteen are rejected
for insufficient seats, no thread fails for any other reason, the flight ends at zero seats,
and exactly one booking row exists.

That last assertion is the one that matters most: nineteen threads *reporting* failure is a
different claim from nineteen threads *leaving nothing behind*.

The same file proves the settlement side: two reconciliations racing on one declined booking
release its seats exactly once. Both tests are integration tests rather than Mockito tests
because a mock cannot contend. A mock can verify that `reserveSeats` was called; it cannot
show what Postgres does when twenty transactions reach the same row.

### Seeing the conditional update by hand

```bash
# drain a flight to one seat, then try to book two
docker compose exec db psql -U aerocore -d aerocore \
  -c "UPDATE flights SET available_seats = 1 WHERE id = 1;"
```

Booking two seats now returns 409 with the true remaining count. Booking one succeeds; the
same request replayed with the same `Idempotency-Key` returns the identical response and does
not reserve a second seat.

The API reference is at `/swagger-ui.html` on the running application.

---

## Where to look

- [`docs/adr`](docs/adr/) — the reasoning behind the five decisions above
- `BookingCheckoutService` — the three phases and where the transactions start and stop
- `FlightRepository.reserveSeats` — the conditional update, and why `@Modifying` carries the
  flags it does
- `ConcurrentBookingIT` — twenty threads against the last seat, and two settlements against
  one booking
- `BookingStatus` — the transition table, including the edge that is deliberately absent
- `IdempotencyStatus` — three states, and why an unresolved payment is not a failed one
- `HoldExpirySweeper` — reclaiming abandoned holds across three replicas, claiming rows with
  `SKIP LOCKED` so no two workers take the same batch
- `PaymentReconciler` / `PaymentReconciliationService` — the opposite trade: candidates are
  selected without a lock because a gateway call follows, and the lock is taken afterwards,
  around the settlement itself
- `.github/workflows/ci.yml` — what is actually proven end to end
