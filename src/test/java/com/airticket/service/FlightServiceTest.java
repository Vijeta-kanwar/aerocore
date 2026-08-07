package com.aerocore.service;

import com.aerocore.TestFixtures;
import com.aerocore.dto.FlightRequest;
import com.aerocore.exception.DuplicateFlightException;
import com.aerocore.exception.ResourceNotFoundException;
import com.aerocore.model.Flight;
import com.aerocore.repository.FlightRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FlightService")
class FlightServiceTest {

    @Mock
    private FlightRepository flightRepository;

    @InjectMocks
    private FlightService flightService;

    private FlightRequest request(String flightNumber, int totalSeats) {
        return new FlightRequest(flightNumber, "Air India", "Delhi", "Mumbai",
                LocalTime.of(6, 0), LocalTime.of(8, 15), new BigDecimal("5499.00"), totalSeats);
    }

    @Test
    @DisplayName("starts a new flight with every seat available")
    void createsFlightFullyAvailable() {
        when(flightRepository.existsByFlightNumber("AI101")).thenReturn(false);
        when(flightRepository.save(any(Flight.class))).thenAnswer(call -> call.getArgument(0));

        Flight created = flightService.create(request("AI101", 180));

        assertThat(created.getTotalSeats()).isEqualTo(180);
        assertThat(created.getAvailableSeats()).isEqualTo(180);
    }

    @Test
    @DisplayName("refuses a duplicate flight number instead of failing at the database")
    void rejectsDuplicateFlightNumber() {
        when(flightRepository.existsByFlightNumber("AI101")).thenReturn(true);

        assertThatThrownBy(() -> flightService.create(request("AI101", 180)))
                .isInstanceOf(DuplicateFlightException.class);

        verify(flightRepository, never()).save(any());
    }

    @Test
    @DisplayName("keeps sold seats sold when capacity is edited")
    void updatePreservesSoldSeats() {
        Flight flight = TestFixtures.flight(1L, 180, 150);
        when(flightRepository.findById(1L)).thenReturn(Optional.of(flight));
        when(flightRepository.findByFlightNumber("AI101")).thenReturn(Optional.of(flight));
        when(flightRepository.save(any(Flight.class))).thenAnswer(call -> call.getArgument(0));

        Flight updated = flightService.update(1L, request("AI101", 200));

        assertThat(updated.getTotalSeats()).isEqualTo(200);
        assertThat(updated.getAvailableSeats()).isEqualTo(170);
    }

    @Test
    @DisplayName("never reports negative availability when capacity shrinks below seats sold")
    void updateClampsAvailabilityAtZero() {
        Flight flight = TestFixtures.flight(1L, 180, 10);
        when(flightRepository.findById(1L)).thenReturn(Optional.of(flight));
        when(flightRepository.findByFlightNumber("AI101")).thenReturn(Optional.of(flight));
        when(flightRepository.save(any(Flight.class))).thenAnswer(call -> call.getArgument(0));

        Flight updated = flightService.update(1L, request("AI101", 50));

        assertThat(updated.getAvailableSeats()).isZero();
    }

    @Test
    @DisplayName("reports a missing flight as not found")
    void findByIdRejectsUnknownId() {
        when(flightRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> flightService.findById(42L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
