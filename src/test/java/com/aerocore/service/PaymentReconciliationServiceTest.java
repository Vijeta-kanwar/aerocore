package com.aerocore.service;

import com.aerocore.model.Booking;
import com.aerocore.model.BookingStatus;
import com.aerocore.model.Flight;
import com.aerocore.model.User;
import com.aerocore.payment.PaymentGateway.PaymentResult;
import com.aerocore.repository.BookingRepository;
import com.aerocore.repository.FlightRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.aerocore.TestFixtures;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional; 

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentReconciliationServiceTest {

    private final BookingRepository bookingRepository =
            mock(BookingRepository.class);

    private final FlightRepository flightRepository =
            mock(FlightRepository.class);

    private final IdempotencyService idempotencyService =
            mock(IdempotencyService.class);

    private final ObjectMapper objectMapper =
        new ObjectMapper()
                .registerModule(new JavaTimeModule());

    private final PaymentReconciliationService service =
            new PaymentReconciliationService(
                    bookingRepository,
                    flightRepository,
                    idempotencyService,
                    objectMapper
            );

   @Test
void succeededOutcomeConfirmsBookingAndCompletesIdempotency() {

    // arrange
    Flight flight = TestFixtures.flight(10L);

    Booking booking = TestFixtures.booking(
            100L,
            flight,
            2
    );

    booking.beginPayment();

    when(bookingRepository.findBookingByIdForUpdate(100L))
            .thenReturn(Optional.of(booking));

    PaymentResult result =
            PaymentResult.succeeded("ch_123");

    // act
    boolean resolved = service.applyOutcome(
            100L,
            result
    );

    // assert
    assertTrue(resolved);
    assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
    assertEquals("ch_123", booking.getPaymentChargeId());

    verify(idempotencyService)
            .completeCheckoutByBookingId(
                    eq(100L),
                    anyString()
            );
}

@Test
void declinedOutcomeCancelsBookingReleasesSeatsAndFailsIdempotency() {

    // arrange
    Flight flight = TestFixtures.flight(10L);

    Booking booking = TestFixtures.booking(
            100L,
            flight,
            2
    );

    booking.beginPayment();

    when(bookingRepository.findBookingByIdForUpdate(100L))
            .thenReturn(Optional.of(booking));

    when(flightRepository.findByIdForUpdate(flight.getId()))
            .thenReturn(Optional.of(flight));

    PaymentResult result =
            PaymentResult.declined("card declined");

    // act
    boolean resolved = service.applyOutcome(
            100L,
            result
    );

    // assert
    assertTrue(resolved);
    assertEquals(BookingStatus.CANCELLED, booking.getStatus());

    verify(flightRepository)
            .findByIdForUpdate(flight.getId());

    verify(idempotencyService)
            .failCheckoutByBookingId(
                    eq(100L),
                    anyString()
            );
}
@Test
void notFoundOutcomeCancelsBookingReleasesSeatsAndFailsIdempotency() {

    // arrange
    Flight flight = TestFixtures.flight(10L);

    Booking booking = TestFixtures.booking(
            101L,
            flight,
            2
    );

    booking.beginPayment();

    when(bookingRepository.findBookingByIdForUpdate(101L))
            .thenReturn(Optional.of(booking));

    when(flightRepository.findByIdForUpdate(flight.getId()))
            .thenReturn(Optional.of(flight));

    PaymentResult result =
        PaymentResult.notFound();

    // act
    boolean resolved = service.applyOutcome(
            101L,
            result
    );

    // assert
    assertTrue(resolved);
    assertEquals(BookingStatus.CANCELLED, booking.getStatus());

    verify(flightRepository)
            .findByIdForUpdate(flight.getId());

    verify(idempotencyService)
            .failCheckoutByBookingId(
                    eq(101L),
                    anyString()
            );
}
@Test
void unknownOutcomeLeavesBookingPendingAndDoesNotReleaseSeats() {

    // arrange
    Flight flight = TestFixtures.flight(10L);

    Booking booking = TestFixtures.booking(
            102L,
            flight,
            2
    );

    booking.beginPayment();

    when(bookingRepository.findBookingByIdForUpdate(102L))
            .thenReturn(Optional.of(booking));

    PaymentResult result =
            PaymentResult.unknown("gateway timeout");

    // act
    boolean resolved = service.applyOutcome(
            102L,
            result
    );

    // assert
    assertFalse(resolved);
    assertEquals(
            BookingStatus.PAYMENT_PENDING,
            booking.getStatus()
    );

    verify(flightRepository, never())
            .findByIdForUpdate(anyLong());

    verifyNoInteractions(idempotencyService);
}

@Test
void alreadyResolvedBookingIsNoOp() {

    // arrange
    Flight flight = TestFixtures.flight(10L);

    Booking booking = TestFixtures.booking(
            103L,
            flight,
            2
    );

    booking.beginPayment();
    booking.confirm("ch_existing");

    when(bookingRepository.findBookingByIdForUpdate(103L))
            .thenReturn(Optional.of(booking));

    PaymentResult result =
            PaymentResult.succeeded("ch_new");

    // act
    boolean resolved = service.applyOutcome(
            103L,
            result
    );

    // assert
    assertFalse(resolved);

    assertEquals(
            BookingStatus.CONFIRMED,
            booking.getStatus()
    );

    assertEquals(
            "ch_existing",
            booking.getPaymentChargeId()
    );

    verifyNoInteractions(flightRepository);
    verifyNoInteractions(idempotencyService);
}

@Test
void missingBookingIsNoOp() {

    // arrange
    when(bookingRepository.findBookingByIdForUpdate(999L))
            .thenReturn(Optional.empty());

    PaymentResult result =
            PaymentResult.succeeded("ch_missing");

    // act
    boolean resolved = service.applyOutcome(
            999L,
            result
    );

    // assert
    assertFalse(resolved);

    verify(bookingRepository)
            .findBookingByIdForUpdate(999L);

    verifyNoInteractions(flightRepository);
    verifyNoInteractions(idempotencyService);
}
}