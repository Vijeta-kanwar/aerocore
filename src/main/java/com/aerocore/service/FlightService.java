package com.aerocore.service;

import com.aerocore.dto.FlightRequest;
import com.aerocore.model.Flight;
import com.aerocore.exception.DuplicateFlightException;
import com.aerocore.exception.ResourceNotFoundException;
import com.aerocore.repository.FlightRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class FlightService {

    private final FlightRepository flightRepository;

    // Constructor injection, not @Autowired on fields: makes the dependency explicit
    // and lets the class be unit-tested without a Spring context.
    public FlightService(FlightRepository flightRepository) {
        this.flightRepository = flightRepository;
    }

    public List<Flight> findAll() {
        return flightRepository.findAll();
    }

    public Flight findById(Long id) {
        return flightRepository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.flight(id));
    }

    public List<Flight> search(String origin, String destination) {
        return flightRepository.search(origin.trim(), destination.trim());
    }

    @Transactional
    public Flight create(FlightRequest request) {
        if (flightRepository.existsByFlightNumber(request.flightNumber())) {
            throw new DuplicateFlightException(request.flightNumber());
        }
        Flight flight = new Flight(
                request.flightNumber(),
                request.airline(),
                request.origin(),
                request.destination(),
                request.departureTime(),
                request.arrivalTime(),
                request.price(),
                request.totalSeats());
        return flightRepository.save(flight);
    }

    @Transactional
    public Flight update(Long id, FlightRequest request) {
        Flight flight = findById(id);

        flightRepository.findByFlightNumber(request.flightNumber())
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(existing -> {
                    throw new DuplicateFlightException(request.flightNumber());
                });

        int seatsSold = flight.getTotalSeats() - flight.getAvailableSeats();

        flight.setFlightNumber(request.flightNumber());
        flight.setAirline(request.airline());
        flight.setOrigin(request.origin());
        flight.setDestination(request.destination());
        flight.setDepartureTime(request.departureTime());
        flight.setArrivalTime(request.arrivalTime());
        flight.setPrice(request.price());
        flight.setTotalSeats(request.totalSeats());
        // Keep already-sold seats sold, even if capacity was edited downward.
        flight.setAvailableSeats(Math.max(request.totalSeats() - seatsSold, 0));

        return flightRepository.save(flight);
    }

    @Transactional
    public void delete(Long id) {
        Flight flight = findById(id);
        flightRepository.delete(flight);
    }
}
