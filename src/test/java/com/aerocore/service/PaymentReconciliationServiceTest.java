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
}