-- V3: the booking lifecycle becomes a real state machine.
--
-- V1 gave a booking two states, so "not cancelled" was a safe stand-in for
-- "still holding seats". That equivalence dies here. A hold that times out has
-- already returned its seats but was never cancelled, so any code asking
-- isCancelled() to answer a seats question would credit those seats twice.
--
--   PENDING    seats are held, payment not settled. The only state with a deadline.
--   CONFIRMED  paid; the seats are the passenger's
--   CANCELLED  given up by the passenger, from PENDING or CONFIRMED
--   EXPIRED    reclaimed by the system because the hold ran out
--
-- Nothing writes PENDING yet -- the create path still produces CONFIRMED. This
-- migration only makes the states legal, so it cannot change today's behaviour.

ALTER TABLE bookings
    ADD COLUMN hold_expires_at TIMESTAMPTZ;

COMMENT ON COLUMN bookings.hold_expires_at IS
    'Deadline for an unpaid PENDING hold. NULL in every other state.';

ALTER TABLE bookings DROP CONSTRAINT ck_bookings_status;

ALTER TABLE bookings
    ADD CONSTRAINT ck_bookings_status
    CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED'));

-- Two failures this rules out, in one line each.
-- A PENDING row with no deadline is invisible to the sweeper and sits on a seat
-- forever. A settled row that still carries a deadline is a paid booking the
-- sweeper will cheerfully expire. Both are the kind of bug that surfaces weeks
-- later in production, so the database refuses them outright.
ALTER TABLE bookings
    ADD CONSTRAINT ck_bookings_hold_expiry
    CHECK (
        (status =  'PENDING' AND hold_expires_at IS NOT NULL)
        OR
        (status <> 'PENDING' AND hold_expires_at IS NULL)
    );

-- The sweeper will ask one question on a loop: which holds have died?
-- A partial index covers only the live PENDING rows, so it stays small no matter
-- how many settled bookings pile up behind it.
CREATE INDEX idx_bookings_expiring_holds
    ON bookings (hold_expires_at)
    WHERE status = 'PENDING';
