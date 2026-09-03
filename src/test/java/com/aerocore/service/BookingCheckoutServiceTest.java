package com.aerocore.service;

import com.aerocore.TestFixtures;
import com.aerocore.dto.BookingRequest;
import com.aerocore.dto.BookingResponse;
import com.aerocore.exception.IdempotencyKeyReusedException;
import com.aerocore.exception.PaymentDeclinedException;
import com.aerocore.payment.PaymentGateway;
import com.aerocore.payment.PaymentGateway.PaymentResult;
import com.aerocore.model.Booking;
import com.aerocore.model.IdempotencyRecord;
import com.aerocore.repository.IdempotencyRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingCheckoutService")
class BookingCheckoutServiceTest {

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private BookingPaymentService paymentService;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private IdempotencyRecordRepository recordRepository;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules();

    private BookingCheckoutService checkoutService;

    private BookingRequest request;

    @BeforeEach
    void setUp() {
        checkoutService = new BookingCheckoutService(
                idempotencyService,
                paymentService,
                paymentGateway,
                recordRepository,
                objectMapper
        );

        request = new BookingRequest(
                1L,
                "Vijeta Kanwar",
                "vijeta@example.com",
                "9876543210",
                2
        );
    }

    @Test
    @DisplayName("definite payment decline releases seats and records FAILED")
    void definiteDeclineReleasesSeatsAndRecordsFailure() {
        String key = "checkout-123";

        Booking hold = TestFixtures.booking(
                10L,
                TestFixtures.flight(1L, 180, 100),
                2
        );

        when(recordRepository.findByIdempotencyKey(key))
                .thenReturn(Optional.empty());

        when(idempotencyService.beginCheckout(
                eq(key),
                any(String.class),
                eq(request)
        )).thenReturn(hold);

        when(paymentGateway.charge(
                eq(hold.getReference()),
                eq(hold.getTotalAmount())
        )).thenReturn(
                PaymentResult.declined("Card was declined")
        );

        assertThatThrownBy(() -> checkoutService.checkout(key, request))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessage("Card was declined");

        verify(paymentService)
                .releaseAfterFailedPayment(hold.getId());

        ArgumentCaptor<String> responseCaptor =
                ArgumentCaptor.forClass(String.class);

        verify(idempotencyService).failCheckout(
                eq(key),
                eq(hold.getId()),
                responseCaptor.capture()
        );

        String storedFailure = responseCaptor.getValue();

        assertThat(storedFailure)
                .contains(hold.getReference())
                .contains("Card was declined");

        verify(paymentGateway).charge(
                hold.getReference(),
                hold.getTotalAmount()
        );

        verify(idempotencyService, never()).completeCheckout(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("unknown payment outcome keeps the booking open and does not release seats")
    void unknownOutcomeLeavesCheckoutInProgress() {
        String key = "checkout-unknown";

        Booking hold = TestFixtures.booking(
                10L,
                TestFixtures.flight(1L, 180, 100),
                2
        );

        when(recordRepository.findByIdempotencyKey(key))
                .thenReturn(Optional.empty());

        when(idempotencyService.beginCheckout(
                eq(key),
                any(String.class),
                eq(request)
        )).thenReturn(hold);

        when(paymentGateway.charge(
                eq(hold.getReference()),
                eq(hold.getTotalAmount())
        )).thenReturn(
                PaymentResult.unknown("Gateway timed out")
        );

        assertThatThrownBy(() -> checkoutService.checkout(key, request))
                .isInstanceOf(com.aerocore.exception.PaymentFailedException.class)
                .hasMessageContaining("could not confirm");

        verify(paymentService, never())
                .releaseAfterFailedPayment(any());

        verify(idempotencyService, never()).failCheckout(
                any(),
                any(),
                any()
        );

        verify(idempotencyService, never()).completeCheckout(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("completed checkout is replayed without calling payment gateway")
    void completedReplayDoesNotCallPaymentGateway() throws Exception {
        String key = "checkout-completed";

        /*
         * The existing record must contain the hash of the SAME request.
         * Otherwise the production code correctly treats this as
         * idempotency-key reuse with a different request.
         */
        String requestHash = calculateHash(request);

        Booking booking = TestFixtures.booking(
                10L,
                TestFixtures.flight(1L, 180, 100),
                2
        );

        booking.beginPayment();
        booking.confirm("charge-123");

        BookingResponse expectedResponse =
                BookingResponse.from(booking);

        String responseBody =
                objectMapper.writeValueAsString(expectedResponse);

        IdempotencyRecord record =
                new IdempotencyRecord(key, requestHash);

        record.complete(
                booking.getId(),
                responseBody
        );

        when(recordRepository.findByIdempotencyKey(key))
                .thenReturn(Optional.of(record));

        BookingResponse actualResponse =
                checkoutService.checkout(key, request);

        assertThat(actualResponse)
                .usingRecursiveComparison()
                .isEqualTo(expectedResponse);

        verify(paymentGateway, never()).charge(
                any(),
                any()
        );
    }

    @Test
    @DisplayName("reusing a key with a different request is rejected")
    void rejectsDifferentRequestForSameKey() {
        String key = "checkout-reused";

        IdempotencyRecord record =
                new IdempotencyRecord(
                        key,
                        "some-other-request-hash"
                );

        when(recordRepository.findByIdempotencyKey(key))
                .thenReturn(Optional.of(record));

        assertThatThrownBy(() -> checkoutService.checkout(key, request))
                .isInstanceOf(IdempotencyKeyReusedException.class);

        verify(paymentGateway, never()).charge(
                any(),
                any()
        );

        verify(idempotencyService, never()).beginCheckout(
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("losing a duplicate-key race re-reads the winner and replays it")
    void duplicateRaceReadsWinnerAndReplays() throws Exception {
        String key = "checkout-race";

        String requestHash = calculateHash(request);

        Booking booking = TestFixtures.booking(
                10L,
                TestFixtures.flight(1L, 180, 100),
                2
        );

        booking.beginPayment();
        booking.confirm("charge-456");

        BookingResponse response =
                BookingResponse.from(booking);

        String responseBody =
                objectMapper.writeValueAsString(response);

        IdempotencyRecord winner =
                new IdempotencyRecord(key, requestHash);

        winner.complete(
                booking.getId(),
                responseBody
        );

        when(recordRepository.findByIdempotencyKey(key))
                .thenReturn(
                        Optional.empty(),
                        Optional.of(winner)
                );

        when(idempotencyService.beginCheckout(
                eq(key),
                eq(requestHash),
                eq(request)
        )).thenThrow(
                new DataIntegrityViolationException("duplicate key")
        );

        BookingResponse replayed =
                checkoutService.checkout(key, request);

        assertThat(replayed)
                .usingRecursiveComparison()
                .isEqualTo(response);

        verify(idempotencyService).beginCheckout(
                key,
                requestHash,
                request
        );

        verify(paymentGateway, never()).charge(
                any(),
                any()
        );
    }

    private String calculateHash(BookingRequest request) throws Exception {
        byte[] json = objectMapper.writeValueAsBytes(request);

        byte[] digest = java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(json);

        return java.util.HexFormat
                .of()
                .formatHex(digest);
    }
}