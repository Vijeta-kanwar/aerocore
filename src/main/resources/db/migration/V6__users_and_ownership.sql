-- V6: bookings get an owner, and endpoints that change them start asking who you are.
--
-- Until now any caller who could guess a booking id could cancel someone else's flight.
-- The README listed that under known limits; this is where it stops being true.
--
-- The interesting decision here is what to do with bookings that already exist. In a live
-- system the answer is a nullable column and a backfill when each passenger registers --
-- their email is the only link between an old booking and a future account. This project
-- had seven rows, all of them named Test or Ghost, so paying for that complexity would buy
-- nothing. The development data goes, user_id is NOT NULL from the start, and no ownership
-- check anywhere has to reason about a booking that belongs to nobody.

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(160) NOT NULL,

    -- BCrypt output: always 60 characters, and never the password itself. A database dump
    -- should be embarrassing, not catastrophic.
    password_hash VARCHAR(72)  NOT NULL,
    full_name     VARCHAR(120) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,

    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_role CHECK (role IN ('USER', 'ADMIN'))
);

-- Login looks up by email on every attempt, and the unique constraint above already gives
-- us that index. Emails are stored lowercased at the write path so the lookup can use it.

-- Development data, deliberately discarded -- see the note at the top of this file.
DELETE FROM idempotency_keys;
DELETE FROM bookings;

ALTER TABLE bookings
    ADD COLUMN user_id BIGINT NOT NULL;

ALTER TABLE bookings
    ADD CONSTRAINT fk_bookings_user FOREIGN KEY (user_id) REFERENCES users (id);

-- "Show me my bookings" is the most common authenticated query in the app, and it replaces
-- the old lookup by passenger email -- which anyone could run for any address.
CREATE INDEX idx_bookings_user_id ON bookings (user_id, booked_at DESC);
