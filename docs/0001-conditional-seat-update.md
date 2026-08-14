# ADR 0001: Reserve seats with a conditional update, not a row lock

## Status

Accepted — 2026-08

## Context

Booking a seat is a read-then-write on a counter that every other booking for the same
flight also wants. Done naively — read the available seats, check there are enough, write the
new value — two transactions can both read "2 seats left", both pass the check, and both
decrement. The flight oversells.

The original implementation solved this with a pessimistic row lock: `SELECT ... FOR UPDATE`
on the flight, then the check and the write in Java. That was correct. Nothing was overselling.

What made it worth revisiting is how long the lock is held. Taking it at the start of the
transaction means the row stays locked through the seat check, the price calculation, the
reference generation and the booking insert — every other booking for that flight queues
behind all of it, not just behind the part that actually touches the counter.

## Decision

Push the condition into the statement and let the database decide:

```sql
UPDATE flights
   SET available_seats = available_seats - :seats
 WHERE id = :flightId
   AND available_seats >= :seats
```

The application reads the affected-row count: 1 means the seats were reserved, 0 means they
were not.

The safety comes from the `WHERE` clause rather than from a lock we asked for. Postgres still
locks the row — every `UPDATE` locks what it modifies — but only for the duration of that one
statement. When a second transaction reaches the same row it blocks there, and once the first
commits, Postgres re-evaluates the second transaction's `WHERE` against the newly committed
value. If the winner took the last seat, `available_seats >= :seats` is now false, the second
statement matches nothing, and the caller gets 0 rows. There is no window between reading the
count and acting on it, because reading and acting are the same statement.

Zero rows is ambiguous — the flight may be full, or the id may not exist — so `create()`
reads the flight first. Not to decide anything: that read establishes existence, so a later
zero can only mean "full". Without it a booking for a nonexistent flight would return 409
"only 0 seats remain" instead of 404.

## Consequences

The booking path holds a lock for the length of one statement instead of a whole transaction,
so concurrent bookings for a popular flight spend far less time queued.

In exchange, success is now something the application has to check for. Under the lock,
getting past the `if` meant the reservation had happened. Now the statement can legitimately
do nothing, and ignoring the row count would create a booking for seats that were never
reserved — silently, with no exception and no log line, until the numbers stop reconciling.

`cancel()` deliberately still uses `SELECT ... FOR UPDATE`, and the two patterns are fitted
to different problems rather than left inconsistent. Releasing a seat has no "only if" to
push into a `WHERE` — you are always giving the seat back — and it changes two tables, the
booking's status and the flight's counter. A single `UPDATE` cannot do both, so a transaction
has to wrap them anyway; holding a lock inside a transaction that already exists costs
nothing extra.

Both approaches still serialise writers on one row. A single hot row in Postgres handles
roughly a few thousand writes a second, which no single flight in this system will approach,
so the ceiling is accepted rather than engineered around. If one ever did, the move would be
per-seat inventory — a row per physical seat, claimed with `SKIP LOCKED` so concurrent
bookings take different rows instead of contending on one counter. That costs 180 rows per
flight instead of one column, plus seat-assignment logic this system does not currently have,
which is why it has not been built.
