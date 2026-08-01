package com.airticket.controller;

import com.airticket.dto.FlightRequest;
import com.airticket.dto.FlightResponse;
import com.airticket.service.FlightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/flights")
@Validated
@Tag(name = "Flights", description = "Search and manage the flight schedule")
public class FlightController {

    private final FlightService flightService;

    public FlightController(FlightService flightService) {
        this.flightService = flightService;
    }

    @GetMapping
    @Operation(summary = "List every flight in the schedule")
    public List<FlightResponse> list() {
        return flightService.findAll().stream().map(FlightResponse::from).toList();
    }

    @GetMapping("/search")
    @Operation(summary = "Find flights on a route")
    public List<FlightResponse> search(@RequestParam @NotBlank String origin,
                                       @RequestParam @NotBlank String destination) {
        return flightService.search(origin, destination).stream().map(FlightResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one flight by id")
    public FlightResponse get(@PathVariable Long id) {
        return FlightResponse.from(flightService.findById(id));
    }

    @PostMapping
    @Operation(summary = "Add a flight to the schedule")
    public ResponseEntity<FlightResponse> create(@Valid @RequestBody FlightRequest request,
                                                 UriComponentsBuilder uriBuilder) {
        FlightResponse created = FlightResponse.from(flightService.create(request));
        URI location = uriBuilder.path("/api/flights/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a flight's details")
    public FlightResponse update(@PathVariable Long id, @Valid @RequestBody FlightRequest request) {
        return FlightResponse.from(flightService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a flight from the schedule")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        flightService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
