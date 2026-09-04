-- A successful payment remains associated with the booking even if
-- the booking is later cancelled.
ALTER TABLE bookings
    DROP CONSTRAINT ck_bookings_charge_id;

ALTER TABLE bookings
    ADD CONSTRAINT ck_bookings_charge_id
    CHECK (
        payment_charge_id IS NULL
        OR status IN ('CONFIRMED', 'CANCELLED')
    );