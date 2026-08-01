-- Baseline schema. Never edited after it ships; changes go in a new V-numbered file.

CREATE TABLE flights (
    id              BIGSERIAL PRIMARY KEY,
    flight_number   VARCHAR(10)    NOT NULL,
    airline         VARCHAR(60)    NOT NULL,
    origin          VARCHAR(60)    NOT NULL,
    destination     VARCHAR(60)    NOT NULL,
    departure_time  TIME           NOT NULL,
    arrival_time    TIME           NOT NULL,
    price           NUMERIC(10, 2) NOT NULL CHECK (price > 0),
    total_seats     INTEGER        NOT NULL CHECK (total_seats >= 0),
    available_seats INTEGER        NOT NULL CHECK (available_seats >= 0),
    version         BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT uq_flights_flight_number UNIQUE (flight_number),
    -- The database refuses to oversell even if application code has a bug.
    CONSTRAINT ck_flights_seats_within_capacity CHECK (available_seats <= total_seats)
);

CREATE INDEX idx_flights_route ON flights (LOWER(origin), LOWER(destination));

CREATE TABLE bookings (
    id              BIGSERIAL PRIMARY KEY,
    reference       VARCHAR(12)    NOT NULL,
    flight_id       BIGINT         NOT NULL,
    passenger_name  VARCHAR(120)   NOT NULL,
    passenger_email VARCHAR(160)   NOT NULL,
    passenger_phone VARCHAR(20)    NOT NULL,
    seats_booked    INTEGER        NOT NULL CHECK (seats_booked > 0),
    total_amount    NUMERIC(12, 2) NOT NULL CHECK (total_amount >= 0),
    status          VARCHAR(16)    NOT NULL,
    booked_at       TIMESTAMPTZ    NOT NULL,

    CONSTRAINT uq_bookings_reference UNIQUE (reference),
    CONSTRAINT fk_bookings_flight FOREIGN KEY (flight_id) REFERENCES flights (id),
    CONSTRAINT ck_bookings_status CHECK (status IN ('CONFIRMED', 'CANCELLED'))
);

CREATE INDEX idx_bookings_passenger_email ON bookings (LOWER(passenger_email));
CREATE INDEX idx_bookings_flight_id ON bookings (flight_id);
