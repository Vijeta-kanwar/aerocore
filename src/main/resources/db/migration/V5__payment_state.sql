-- V5: a booking that has reached the payment gateway is not the same as one waiting
-- for a passenger who already closed the tab.
--
-- The sweeper expires PENDING holds. Once a charge is in flight that becomes dangerous:
-- expiring a booking the customer just paid for takes their seat and their money. But the
-- sweeper cannot tell the two apart, and asking the gateway about every abandoned hold
-- means calling a third party thousands of times to hear "never heard of it" -- and tying
-- our seat inventory to their uptime.
--
-- So the distinction moves into the data. PAYMENT_PENDING is a state the sweeper simply
-- never selects, and a separate slower job reconciles those against the gateway.

ALTER TABLE bookings ADD COLUMN payment_charge_id VARCHAR(64);

COMMENT ON COLUMN bookings.payment_charge_id IS
    'Gateway charge identifier, set once a payment is known to have succeeded.';

ALTER TABLE bookings DROP CONSTRAINT ck_bookings_status;

ALTER TABLE bookings
    ADD CONSTRAINT ck_bookings_status
    CHECK (status IN ('PENDING', 'PAYMENT_PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED'));

-- Both live-but-unpaid states keep a deadline: PENDING so the sweeper can reclaim it,
-- PAYMENT_PENDING so the reconciler knows how long a charge has been unresolved.
ALTER TABLE bookings DROP CONSTRAINT ck_bookings_hold_expiry;

ALTER TABLE bookings
    ADD CONSTRAINT ck_bookings_hold_expiry
    CHECK (
        (status IN ('PENDING', 'PAYMENT_PENDING') AND hold_expires_at IS NOT NULL)
        OR
        (status NOT IN ('PENDING', 'PAYMENT_PENDING') AND hold_expires_at IS NULL)
    );

-- A charge id only makes sense on a booking that was actually paid for.
ALTER TABLE bookings
    ADD CONSTRAINT ck_bookings_charge_id
    CHECK (payment_charge_id IS NULL OR status = 'CONFIRMED');

-- The reconciler's working set. Same partial-index trick as the sweeper's: this covers the
-- handful of charges currently unresolved, never the millions that settled.
CREATE INDEX idx_bookings_unresolved_payments
    ON bookings (hold_expires_at)
    WHERE status = 'PAYMENT_PENDING';
