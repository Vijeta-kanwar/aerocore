package com.airticket.exception;

/** Maps to HTTP 409. */
public class DuplicateFlightException extends RuntimeException {

    public DuplicateFlightException(String flightNumber) {
        super("Flight " + flightNumber + " already exists");
    }
}
