-- V4: idempotency keys, so a retried booking request cannot become a second booking.
--
-- The problem this solves is invisible to everything built so far. A user taps Book,
-- the server commits the booking, and the connection drops before the response gets
-- home. The user taps again. Both requests are valid, both find seats, both succeed --
-- and the seat logic was right every time. It correctly reserved two seats because it
-- correctly received two requests.
--
-- The database cannot tell a retry from a genuine second booking; they are identical.
-- Only the client knows, so the client sends a key and the server remembers it.

CREATE TABLE idempotency_keys (
    id              BIGSERIAL PRIMARY KEY,

    -- Supplied by the client, one per user intent. The UNIQUE constraint below is what
    -- actually enforces "once": two simultaneous requests both find no existing row, but
    -- only one of them can insert it. Same shape as the conditional seat update -- the
    -- database decides, not an if-statement in Java.
    idempotency_key VARCHAR(255) NOT NULL,

    -- SHA-256 of the request body. Lets us catch a client reusing one key for a different
    -- request, which is a bug worth surfacing rather than papering over.
    request_hash    VARCHAR(64)  NOT NULL,

    status          VARCHAR(16)  NOT NULL,

    -- Populated once the work succeeds. This is how a replay finds the original booking
    -- instead of creating another one.
    booking_id      BIGINT,

    -- The exact response the first request produced. A replay returns this, not a freshly
    -- rendered view of the booking -- otherwise a booking cancelled in between would make
    -- the "same" request return two different answers.
    response_body   TEXT,

    created_at      TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ,

    CONSTRAINT uq_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_idempotency_booking FOREIGN KEY (booking_id) REFERENCES bookings (id),
    CONSTRAINT ck_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),

    -- A completed record without its result is useless to a replay, and an in-progress one
    -- has no result yet. Saying so here means no code path can leave a half-written row.
    CONSTRAINT ck_idempotency_completion CHECK (
        (status = 'COMPLETED'   AND booking_id IS NOT NULL AND response_body IS NOT NULL
                                AND completed_at IS NOT NULL)
        OR
        (status = 'IN_PROGRESS' AND booking_id IS NULL     AND response_body IS NULL
                                AND completed_at IS NULL)
    )
);

-- Keys are worth expiring eventually -- a retry arriving a week later is a new intent, not
-- a duplicate. Nothing prunes this table yet; the index is here so the sweeper we build for
-- expired holds can clean these up cheaply too.
CREATE INDEX idx_idempotency_created_at ON idempotency_keys (created_at);
