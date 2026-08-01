package com.airticket.controller;

import com.airticket.TestFixtures;
import com.airticket.dto.BookingRequest;
import com.airticket.exception.InsufficientSeatsException;
import com.airticket.exception.ResourceNotFoundException;
import com.airticket.model.Booking;
import com.airticket.model.Flight;
import com.airticket.service.BookingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BookingController.class)
@DisplayName("POST /api/bookings")
class BookingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BookingService bookingService;

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    @Test
    @DisplayName("returns 201 with a Location header and the booking reference")
    void createsBooking() throws Exception {
        Flight flight = TestFixtures.flight(1L);
        Booking booking = TestFixtures.booking(10L, flight, 2);
        when(bookingService.create(any(BookingRequest.class))).thenReturn(booking);

        BookingRequest request = new BookingRequest(1L, "Vijeta Kanwar",
                "vijeta@example.com", "9876543210", 2);

        mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value("AT-7F3K2Q"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    @DisplayName("returns 400 and names the offending field when the email is malformed")
    void rejectsInvalidEmail() throws Exception {
        BookingRequest request = new BookingRequest(1L, "Vijeta Kanwar",
                "not-an-email", "9876543210", 2);

        mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.passengerEmail").exists());
    }

    @Test
    @DisplayName("returns 400 when seat count is below one")
    void rejectsZeroSeats() throws Exception {
        BookingRequest request = new BookingRequest(1L, "Vijeta Kanwar",
                "vijeta@example.com", "9876543210", 0);

        mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.seatsBooked").exists());
    }

    @Test
    @DisplayName("returns 409, not 500, when the flight is full")
    void reportsOverbookingAsConflict() throws Exception {
        when(bookingService.create(any(BookingRequest.class)))
                .thenThrow(new InsufficientSeatsException(5, 2));

        BookingRequest request = new BookingRequest(1L, "Vijeta Kanwar",
                "vijeta@example.com", "9876543210", 5);

        mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("only 2 remain")));
    }

    @Test
    @DisplayName("returns 404, not 500, when the flight does not exist")
    void reportsUnknownFlightAsNotFound() throws Exception {
        when(bookingService.create(any(BookingRequest.class)))
                .thenThrow(ResourceNotFoundException.flight(99L));

        BookingRequest request = new BookingRequest(99L, "Vijeta Kanwar",
                "vijeta@example.com", "9876543210", 1);

        mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("returns 400 when the passenger email query parameter is malformed")
    void rejectsInvalidPassengerLookup() throws Exception {
        mockMvc.perform(get("/api/bookings/passenger").param("email", "nonsense"))
                .andExpect(status().isBadRequest());
    }
}
