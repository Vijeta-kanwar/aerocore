Readme · MD
AeroCore

A flight-booking backend built around four things that are easy to get subtly wrong: a seat must not be oversold when two bookings arrive together, one checkout request must not become two charges, a payment whose outcome is never reported must not strand a seat or take money for a booking that no longer exists, and an operation on a booking must belong to the person making it.

The CRUD around flights and bookings is the uninteresting part. What the project is actually about is transaction boundaries, conditional writes, idempotency, and a state machine that holds when things fail halfway.

Java 17 · Spring Boot 3.3 · PostgreSQL 16 · Flyway · Docker · Kubernetes · GitHub Actions

The four invariants

A seat is never oversold. Reservation is a conditional update — UPDATE flights SET available_seats = available_seats - :n WHERE id = :id AND available_seats >= :n — and the affected-row count is the answer. The database decides whether enough seats remained at the moment of the write; nothing is read into Java and then acted upon, so there is no window for another booking to change the count underneath.

One request is one booking. Clients send an Idempotency-Key. The key row and the booking commit in the same transaction, so a crash rolls back both and a retry books cleanly. A replay returns the original response verbatim; a key reused with a different body is a 422, not a silent replay.

A booking only moves along legal edges. Five states, four legal transitions, all declared in one table on BookingStatus. PAYMENT_PENDING → EXPIRED is deliberately not among them: nothing may quietly time out a booking that might already have been paid for.

Nothing is claimed that isn't checked. Ownership is enforced in the service layer, the CI smoke test exercises the real stack against real Postgres, and what is not yet proven is listed under Known limits rather than left for someone to discover.

How a booking works

Three phases, with the transaction boundaries falling between them:

Reserve — one transaction: create the booking as PENDING, reserve the seats, record the idempotency key. Commit.
Pay — no transaction is open. The gateway is called with the booking reference as its idempotency key. This takes seconds; nothing is locked and no connection is held.
Settle — one transaction: confirm the booking, or release the seats. Commit.

The seat is protected during phase 2 by a row that says PENDING, not by a lock. A lock protects for milliseconds; a state protects for minutes, survives a restart, and occupies no connection.

Just before the gateway is called the booking moves to PAYMENT_PENDING, which exists for one reason: the hold sweeper reclaims abandoned PENDING holds, and once money may have moved, "this hold looks abandoned" stops being a safe conclusion. The sweeper never selects PAYMENT_PENDING. A separate reconciler resolves those by asking the gateway what actually happened.

Decisions

Recorded in docs/adr — one file per decision, each with the context that forced it and what it cost:

0001 — reserving seats with a conditional update instead of a row lock, and why cancel() still uses one
0002 — why the key and the booking share a transaction
0003 — why the gateway call sits outside every transaction
0004 — stateless JWTs across three replicas, and what localStorage costs
Bugs worth reading about

The detached idempotency record. reserveSeats is annotated @Modifying(clearAutomatically = true) so a stale seat count can't be read after a bulk update. That clears the entire persistence context, not just the Flight — including the IdempotencyRecord inserted moments earlier in the same transaction. By the time record.complete() ran, the record was detached, dirty checking never saw the change, and no UPDATE was issued. Every replay returned "still in progress", forever. Two individually correct decisions, made a day apart, that broke each other.

Lazy loading, when one transaction became three. Mapping a Booking to its DTO reads flight.getFlightNumber(), and the association is LAZY. That was safe while checkout was a single transaction, because the mapping happened inside it. Splitting checkout around the gateway call moved the mapping outside any session, and the proxy had nothing to load from. Entities belong inside transactions; DTOs travel outside them.

A CHECK constraint that was right for one transition and wrong for the next. payment_charge_id was constrained to rows with status CONFIRMED. True at the moment of payment, false the moment a paid booking was cancelled — the constraint fired and every cancellation of a paid booking became a 500. The charge id records that money moved, and cancelling doesn't unmake that; it is exactly what a refund needs. A CHECK has to hold for every state a row will ever be in.

403 where 401 belonged. The CI smoke test asserted that an unauthenticated booking is rejected with 401 and got 403 instead — Spring Security's stateless default, which tells a client "you're not allowed" when the truth is "I don't know who you are". No unit test could have caught it, because @WithMockUser never exercises the anonymous path. The frontend depends on the distinction: 401 clears the session and shows sign-in, 403 doesn't.

Known limits
No token revocation. Signing out stops sending the token; it doesn't invalidate it. A stolen token works until it expires. The fix is refresh tokens, which need server-side state that access tokens deliberately avoid.
Idempotency keys are never pruned. The table grows without bound. A retry arriving a week later is a new intent, not a duplicate, so keys should expire.
No automated concurrency proof. The design is safe and the unit tests prove the right query is issued, but nothing yet fires twenty threads at the last seat and asserts one success. Knowing the difference between designed-safe and tested-safe is the point of saying so here.
The payment gateway is a stub. Deliberately: what needed testing was latency, declines and outcomes that never arrive, and a real provider hands those out on its own schedule. A real one must accept an idempotency key and answer questions about past charges.
A payment with an unknown outcome holds its seat until the reconciler runs — and indefinitely if the gateway stays unreachable. That is the chosen direction to fail in: a booking stuck for an hour beats a seat sold twice.
One Postgres instance. A stated single point of failure. Production would want an operator like CloudNativePG for replication and failover.
Demo secrets are committed so a clean clone runs. Kubernetes Secrets are base64, not encryption; production would use Sealed Secrets or an external secrets operator.
Running it
bash
docker compose up --build

Then open http://localhost:8080, register an account, and book a flight. Payment happens server-side inside checkout — there is no separate payment step.

To see the concurrency behaviour rather than the happy path:

bash
# drain a flight to one seat, then ask for two
docker compose exec db psql -U aerocore -d aerocore \
  -c "UPDATE flights SET available_seats = 1 WHERE id = 1;"

Booking two seats now returns 409 with the true remaining count. Booking one succeeds; the same request replayed with the same Idempotency-Key returns the identical response and does not reserve a second seat.

The API reference is at /swagger-ui.html on the running application.

Where to look
docs/adr — the reasoning behind the four decisions above
BookingCheckoutService — the three phases and where the transactions start and stop
FlightRepository.reserveSeats — the conditional update, and why @Modifying carries the flags it does
BookingStatus — the transition table, including the edge that is deliberately absent
HoldExpirySweeper / PaymentReconciler — background work across three replicas, with SKIP LOCKED and no coordination
.github/workflows/ci.yml — what is actually proven end to end