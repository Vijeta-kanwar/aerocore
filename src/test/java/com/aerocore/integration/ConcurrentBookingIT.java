package com.aerocore.integration;

import com.aerocore.dto.BookingRequest;
import com.aerocore.exception.InsufficientSeatsException;
import com.aerocore.model.Flight;
import com.aerocore.model.Role;
import com.aerocore.model.User;
import com.aerocore.repository.BookingRepository;
import com.aerocore.repository.FlightRepository;
import com.aerocore.repository.UserRepository;
import com.aerocore.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test the rest of the suite structurally cannot be.
 *
 * <p>Every other test that touches booking uses a mocked repository, and a mock has no
 * concurrency -- it cannot block, cannot hold a row, and cannot re-evaluate a WHERE clause
 * against a value another transaction just committed. Those tests prove the right query is
 * issued. They cannot prove the query does what the design claims, because nothing in them
 * ever contends.
 *
 * <p>So this one runs against real Postgres, fires twenty threads at a single remaining seat,
 * and asserts the outcome the whole project rests on.
 */
@SpringBootTest
@Testcontainers
@DisplayName("Twenty threads, one seat")
class ConcurrentBookingIT {

    private static final int THREADS = 20;

    // The same major version as production. Testing concurrency against a different engine
    // would prove something about that engine, not about what actually runs.
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // Flyway runs against this container exactly as it does against a real database, so
        // the migrations are under test here too.
    }

    @Autowired private BookingService bookingService;
    @Autowired private FlightRepository flightRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private UserRepository userRepository;

    private Long flightId;
    private Long userId;

    @BeforeEach
    void seedOneRemainingSeat() {
        bookingRepository.deleteAll();
        flightRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(
                new User("racer@aerocore.test", "irrelevant-hash", "Race Condition", Role.USER));
        userId = user.getId();

        Flight flight = new Flight(
        "AI101", "Air India", "Delhi", "Mumbai",
        LocalTime.of(6, 0), LocalTime.of(8, 15),
        new BigDecimal("5499.00"), 180);

        flight.setAvailableSeats(1);
        flightId = flightRepository.save(flight).getId();
    }

    @Test
    @DisplayName("exactly one booking succeeds, and the nineteen that fail leave nothing behind")
    void doesNotOversellTheLastSeat() throws InterruptedException {
        AtomicInteger booked = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();

        // Starting threads in a loop is not contention -- the first can finish before the
        // last is created. The latch holds all twenty at the same line until they are
        // released together, so they reach the update genuinely at once.
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(THREADS);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);

        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                // Each thread gets its own security context; SecurityContextHolder is
                // thread-local, so the one seeded on the test thread is invisible here.
                authenticateAs(userId);
                try {
                    startLine.await();
                    bookingService.createHold(requestForOneSeat());
                    booked.incrementAndGet();
                } catch (InsufficientSeatsException expected) {
                    rejected.incrementAndGet();
                } catch (Exception surprising) {
                    // Counted rather than swallowed. A deadlock or a constraint violation is
                    // not "the seat was taken" -- lumping them together would let this test
                    // pass while the system failed in a way nobody meant to allow.
                    unexpected.incrementAndGet();
                } finally {
                    SecurityContextHolder.clearContext();
                    finished.countDown();
                }
            });
        }

        startLine.countDown();
        assertThat(finished.await(30, TimeUnit.SECONDS))
                .as("all threads finished within the timeout")
                .isTrue();
        pool.shutdown();

        assertThat(unexpected.get()).as("no thread failed for a reason other than a full flight").isZero();
        assertThat(booked.get()).as("exactly one booking succeeded").isEqualTo(1);
        assertThat(rejected.get()).as("the other nineteen were cleanly rejected").isEqualTo(THREADS - 1);

        assertThat(flightRepository.findById(flightId).orElseThrow().getAvailableSeats())
                .as("the seat count reached zero and did not go negative")
                .isZero();

        // The assertion that matters most. Nineteen threads reporting failure is not the same
        // claim as nineteen threads leaving nothing behind: a bug where the seat update fails
        // but the booking is inserted anyway would satisfy every assertion above while putting
        // twenty rows in this table.
        assertThat(bookingRepository.findAll())
                .as("a rejected booking leaves no trace")
                .hasSize(1);
    }

    private BookingRequest requestForOneSeat() {
        return new BookingRequest(flightId, "Race Condition", "racer@aerocore.test", "9876543210", 1);
    }

    private void authenticateAs(Long id) {
        var authentication = new UsernamePasswordAuthenticationToken(
                id, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
