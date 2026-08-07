package com.aerocore.controller;

import com.aerocore.TestFixtures;
import com.aerocore.dto.FlightRequest;
import com.aerocore.exception.DuplicateFlightException;
import com.aerocore.exception.ResourceNotFoundException;
import com.aerocore.model.Flight;
import com.aerocore.service.FlightService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FlightController.class)
@DisplayName("/api/flights")
class FlightControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FlightService flightService;

    @Test
    @DisplayName("lists the schedule")
    void listsFlights() throws Exception {
        when(flightService.findAll()).thenReturn(List.of(TestFixtures.flight(1L)));

        mockMvc.perform(get("/api/flights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].flightNumber").value("AI101"))
                .andExpect(jsonPath("$[0].availableSeats").value(180));
    }

    @Test
    @DisplayName("filters by route")
    void searchesByRoute() throws Exception {
        when(flightService.search("Delhi", "Mumbai")).thenReturn(List.of(TestFixtures.flight(1L)));

        mockMvc.perform(get("/api/flights/search")
                        .param("origin", "Delhi")
                        .param("destination", "Mumbai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("returns 400 when a route parameter is missing")
    void rejectsIncompleteSearch() throws Exception {
        mockMvc.perform(get("/api/flights/search").param("origin", "Delhi"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("returns 404 for an unknown flight id")
    void reportsUnknownFlight() throws Exception {
        when(flightService.findById(99L)).thenThrow(ResourceNotFoundException.flight(99L));

        mockMvc.perform(get("/api/flights/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("returns 201 and a Location header for a new flight")
    void createsFlight() throws Exception {
        Flight flight = TestFixtures.flight(1L);
        when(flightService.create(any(FlightRequest.class))).thenReturn(flight);

        FlightRequest request = new FlightRequest("AI101", "Air India", "Delhi", "Mumbai",
                LocalTime.of(6, 0), LocalTime.of(8, 15), new BigDecimal("5499.00"), 180);

        mockMvc.perform(post("/api/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/flights/1")));
    }

    @Test
    @DisplayName("returns 400 for a negative price")
    void rejectsNegativePrice() throws Exception {
        FlightRequest request = new FlightRequest("AI101", "Air India", "Delhi", "Mumbai",
                LocalTime.of(6, 0), LocalTime.of(8, 15), new BigDecimal("-1.00"), 180);

        mockMvc.perform(post("/api/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.price").exists());
    }

    @Test
    @DisplayName("returns 409 for a flight number already in the schedule")
    void reportsDuplicateAsConflict() throws Exception {
        when(flightService.create(any(FlightRequest.class)))
                .thenThrow(new DuplicateFlightException("AI101"));

        FlightRequest request = new FlightRequest("AI101", "Air India", "Delhi", "Mumbai",
                LocalTime.of(6, 0), LocalTime.of(8, 15), new BigDecimal("5499.00"), 180);

        mockMvc.perform(post("/api/flights")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }
}
