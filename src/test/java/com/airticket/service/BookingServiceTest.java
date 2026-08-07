package com.aerocore.service;

import com.aerocore.TestFixtures;
import com.aerocore.dto.BookingRequest;
import com.aerocore.exception.BookingAlreadyCancelledException;
import com.aerocore.exception.InsufficientSeatsException;
import com.aerocore.exception.ResourceNotFoundException;
import com.aerocore.model.Booking;
import com.aerocore.model.BookingStatus;
import com.aerocore.model.Flight;
import com.aerocore.repository.BookingRepository;
import com.aerocore.repository.FlightRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingService")
class BookingServiceTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private FlightRepository flightRepository;

    @Mock
    private BookingReferenceGenerator referenceGenerator;

    @InjectMocks
    private BookingService bookingService;

    private BookingRequest requestFor(long flightId, int seats) {
        return new BookingRequest(flightId, "Vijeta Kanwar", "vijeta@example.com", "9876543210", seats);
    }

    @Test
    @DisplayName("confirms the booking and decrements the flight's seat count")
    void createsBookingAndReservesSeats() {
        Flight flight = TestFixtures.flight(1L, 180, 100);
        when(flightRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(flight));
        when(referenceGenerator.generate()).thenReturn("AT-7F3K2Q");
        when(bookingRepository.existsByReference("AT-7F3K2Q")).thenReturn(false);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(call -> call.getArgument(0));

        Booking booking = bookingService.create(requestFor(1L, 3));

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getSeatsBooked()).isEqualTo(3);
        assertThat(flight.getAvailableSeats()).isEqualTo(97);
    }

    @Test
    @DisplayName("prices the booking as unit price times seat count, exactly")
    void calculatesTotalWithoutFloatingPointDrift() {
        Flight flight = TestFixtures.flight(1L, 180, 100);
        when(flightRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(flight));
        when(referenceGenerator.generate()).thenReturn("AT-7F3K2Q");
        when(bookingRepository.existsByReference("AT-7F3K2Q")).thenReturn(false);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(call -> call.getArgument(0));

        Booking booking = bookingService.create(requestFor(1L, 3));

        assertThat(booking.getTotalAmount()).isEqualByComparingTo(new BigDecimal("16497.00"));
    }

    @Test
    @DisplayName("normalises the passenger email so lookups are case-insensitive")
    void lowercasesEmailOnCreate() {
        Flight flight = TestFixtures.flight(1L, 180, 100);
        when(flightRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(flight));
        when(referenceGenerator.generate()).thenReturn("AT-7F3K2Q");
        when(bookingRepository.existsByReference("AT-7F3K2Q")).thenReturn(false);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(call -> call.getArgument(0));

        BookingRequest request = new BookingRequest(1L, "Vijeta Kanwar",
                "  Vijeta@Example.COM  ", "9876543210", 1);

        Booking booking = bookingService.create(request);

        assertThat(booking.getPassengerEmail()).isEqualTo("vijeta@example.com");
    }

    @Test
    @DisplayName("rejects a booking for more seats than remain, and saves nothing")
    void rejectsOverbooking() {
        Flight flight = TestFixtures.flight(1L, 180, 2);
        when(flightRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(flight));

        assertThatThrownBy(() -> bookingService.create(requestFor(1L, 5)))
                .isInstanceOf(InsufficientSeatsException.class)
                .hasMessageContaining("only 2 remain");

        assertThat(flight.getAvailableSeats()).isEqualTo(2);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    @DisplayName("takes a write lock on the flight rather than a plain read")
    void locksFlightRowBeforeReadingSeatCount() {
        Flight flight = TestFixtures.flight(1L, 180, 100);
        when(flightRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(flight));
        when(referenceGenerator.generate()).thenReturn("AT-7F3K2Q");
        when(bookingRepository.existsByReference("AT-7F3K2Q")).thenReturn(false);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(call -> call.getArgument(0));

        bookingService.create(requestFor(1L, 1));

        verify(flightRepository).findByIdForUpdate(1L);
        verify(flightRepository, never()).findById(any());
    }

    @Test
    @DisplayName("reports a missing flight as not found, not as a server error")
    void rejectsUnknownFlight() {
        when(flightRepository.findByIdForUpdate(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookingService.create(requestFor(99L, 1)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("retries the reference generator until it produces an unused reference")
    void regeneratesReferenceOnCollision() {
        Flight flight = TestFixtures.flight(1L, 180, 100);
        when(flightRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(flight));
        when(referenceGenerator.generate()).thenReturn("AT-AAAAAA", "AT-BBBBBB");
        when(bookingRepository.existsByReference("AT-AAAAAA")).thenReturn(true);
        when(bookingRepository.existsByReference("AT-BBBBBB")).thenReturn(false);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(call -> call.getArgument(0));

        ArgumentCaptor<Booking> saved = ArgumentCaptor.forClass(Booking.class);
        bookingService.create(requestFor(1L, 1));
        verify(bookingRepository).save(saved.capture());

        assertThat(saved.getValue().getReference()).isEqualTo("AT-BBBBBB");
    }

    @Test
    @DisplayName("returns seats to the flight when a booking is cancelled")
    void cancelReleasesSeats() {
        Flight flight = TestFixtures.flight(1L, 180, 95);
        Booking booking = TestFixtures.booking(10L, flight, 5);
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(flightRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(flight));

        Booking cancelled = bookingService.cancel(10L);

        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(flight.getAvailableSeats()).isEqualTo(100);
    }

    @Test
    @DisplayName("refuses a second cancellation so seats are never credited twice")
    void rejectsDoubleCancellation() {
        Flight flight = TestFixtures.flight(1L, 180, 95);
        Booking booking = TestFixtures.booking(10L, flight, 5);
        booking.cancel();
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> bookingService.cancel(10L))
                .isInstanceOf(BookingAlreadyCancelledException.class);

        assertThat(flight.getAvailableSeats()).isEqualTo(95);
        verify(flightRepository, never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("never restores more seats than the aircraft has")
    void releaseIsCappedAtCapacity() {
        Flight flight = TestFixtures.flight(1L, 180, 180);
        Booking booking = TestFixtures.booking(10L, flight, 5);
        when(bookingRepository.findById(10L)).thenReturn(Optional.of(booking));
        when(flightRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(flight));

        bookingService.cancel(10L);

        assertThat(flight.getAvailableSeats()).isEqualTo(180);
    }
}
