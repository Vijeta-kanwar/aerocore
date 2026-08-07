package com.aerocore.exception;

/** Maps to HTTP 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException flight(Long id) {
        return new ResourceNotFoundException("No flight exists with id " + id);
    }

    public static ResourceNotFoundException booking(Long id) {
        return new ResourceNotFoundException("No booking exists with id " + id);
    }
}
