package com.aerocore.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BookingReferenceGenerator")
class BookingReferenceGeneratorTest {

    private final BookingReferenceGenerator generator = new BookingReferenceGenerator();

    @RepeatedTest(20)
    @DisplayName("produces an AT- prefixed reference of a fixed length")
    void matchesExpectedShape() {
        assertThat(generator.generate()).matches("^AT-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{6}$");
    }

    @Test
    @DisplayName("omits characters that are misread when a reference is typed from a printout")
    void excludesAmbiguousCharacters() {
        String joined = IntStream.range(0, 500)
                .mapToObj(i -> generator.generate())
                .reduce("", String::concat);

        assertThat(joined).doesNotContain("0").doesNotContain("O")
                .doesNotContain("1").doesNotContain("I");
    }

    @Test
    @DisplayName("does not repeat itself across a realistic batch")
    void collisionsAreRare() {
        Set<String> seen = new HashSet<>();
        IntStream.range(0, 2000).forEach(i -> seen.add(generator.generate()));

        assertThat(seen).hasSizeGreaterThan(1990);
    }
}
