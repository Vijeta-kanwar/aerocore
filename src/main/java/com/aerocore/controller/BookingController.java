package com.aerocore.controller;

import com.aerocore.dto.BookingRequest;
import com.aerocore.dto.BookingResponse;
import com.aerocore.service.BookingCheckoutService;
import com.aerocore.service.BookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;


import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/bookings")
@Validated
@Tag(name = "Bookings", description = "Reserve and cancel seats")
public class BookingController {

    private final BookingService bookingService;
    private final BookingCheckoutService bookingCheckoutService;

    public BookingController(
            BookingService bookingService,
            BookingCheckoutService bookingCheckoutService) {

        this.bookingService = bookingService;
        this.bookingCheckoutService = bookingCheckoutService;
    }

    @GetMapping
    @Operation(summary = "List every booking")
    public List<BookingResponse> list() {
        return bookingService.findMine()
                .stream()
                .map(BookingResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one booking by id")
    public BookingResponse get(@PathVariable Long id) {
        return BookingResponse.from(bookingService.findById(id));
    }

    @GetMapping("/reference/{reference}")
    @Operation(summary = "Look up a booking by its printed reference")
    public BookingResponse getByReference(@PathVariable String reference) {
        return BookingResponse.from(
                bookingService.findByReference(reference)
        );
    }

    @GetMapping("/me")
public List<BookingResponse> myBookings() {
    return bookingService.findMine().stream().map(BookingResponse::from).toList();
}

    @PostMapping
    public ResponseEntity<BookingResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false)
            String idempotencyKey,
            @Valid @RequestBody BookingRequest request,
            UriComponentsBuilder uriBuilder) {

        BookingResponse created =
                bookingCheckoutService.checkout(idempotencyKey, request);

        URI location = uriBuilder
                .path("/api/bookings/{id}")
                .buildAndExpand(created.id())
                .toUri();

        return ResponseEntity
                .created(location)
                .body(created);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel a booking and return its seats to the flight")
    public BookingResponse cancel(@PathVariable Long id) {
        return BookingResponse.from(bookingService.cancel(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Erase a booking record entirely")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        bookingService.delete(id);
        return ResponseEntity.noContent().build();
    }
}