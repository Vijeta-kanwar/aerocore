-- V8: idempotency records previously modeled only successful completion.
-- A definite payment decline is also a finished outcome and must be persisted
-- so replaying the same key does not incorrectly report "still in progress".
--
-- The constraints are updated to describe every valid state the row can ever
-- have, rather than only the states that existed when V4 was written.

ALTER TABLE idempotency_keys
    DROP CONSTRAINT ck_idempotency_status;

ALTER TABLE idempotency_keys
    ADD CONSTRAINT ck_idempotency_status
    CHECK (status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED'));


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
            AND booking_id IS NULL
            AND response_body IS NULL
            AND completed_at IS NULL
        )
    );