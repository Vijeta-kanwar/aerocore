-- V9: an IN_PROGRESS idempotency record may now be linked to its booking.
--
-- The booking is created before payment is attempted. Keeping its ID on the
-- idempotency record lets reconciliation connect an unresolved payment back
-- to the original request.

ALTER TABLE idempotency_keys
    DROP CONSTRAINT ck_idempotency_completion;

ALTER TABLE idempotency_keys
    ADD CONSTRAINT ck_idempotency_completion
    CHECK (
        (
            status = 'COMPLETED'
            AND booking_id IS NOT NULL
            AND response_body IS NOT NULL
            AND completed_at IS NOT NULL
        )
        OR
        (
            status = 'FAILED'
            AND booking_id IS NOT NULL
            AND response_body IS NOT NULL
            AND completed_at IS NOT NULL
        )
        OR
        (
            status = 'IN_PROGRESS'
            AND response_body IS NULL
            AND completed_at IS NULL
        )
    );