package com.aerocore.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Produces references like AT-7F3K2Q. Ambiguous characters (0/O, 1/I) are excluded
 * so a reference can be read aloud or typed from a printout without errors.
 */
@Component
public class BookingReferenceGenerator {

    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int LENGTH = 6;

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder builder = new StringBuilder("AT-");
        for (int i = 0; i < LENGTH; i++) {
            builder.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
