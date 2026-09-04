package com.aerocore.service;

import com.aerocore.model.Booking;
import com.aerocore.payment.PaymentGateway;
import com.aerocore.payment.PaymentGateway.PaymentResult;
import com.aerocore.repository.BookingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentReconcilerTest {

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private PaymentGateway paymentGateway;

    @Mock
    private PaymentReconciliationService reconciliationService;

    @Test
    void noUnresolvedPaymentsDoesNothing() {
        when(bookingRepository.findUnresolvedPayments(
                any(Instant.class),
                any(Pageable.class)
        )).thenReturn(List.of());

        PaymentReconciler reconciler =
                new PaymentReconciler(
                        bookingRepository,
                        paymentGateway,
                        reconciliationService,
                        2,
                        50
                );

        reconciler.reconcileUnresolvedPayments();

        verifyNoInteractions(paymentGateway);
        verifyNoInteractions(reconciliationService);
    }

    @Test
    void unresolvedPaymentIsLookedUpAndDelegated() {
        Booking booking = org.mockito.Mockito.mock(Booking.class);

        PaymentResult result =
                PaymentResult.declined("card declined");

        when(booking.getId()).thenReturn(100L);
        when(booking.getReference()).thenReturn("AT-TEST-100");

        when(bookingRepository.findUnresolvedPayments(
                any(Instant.class),
                any(Pageable.class)
        )).thenReturn(List.of(booking));

        when(paymentGateway.lookup("AT-TEST-100"))
                .thenReturn(result);

        PaymentReconciler reconciler =
                new PaymentReconciler(
                        bookingRepository,
                        paymentGateway,
                        reconciliationService,
                        2,
                        50
                );

        reconciler.reconcileUnresolvedPayments();

        verify(paymentGateway)
                .lookup("AT-TEST-100");

        verify(reconciliationService)
                .applyOutcome(100L, result);
    }

    @Test
    void multipleUnresolvedPaymentsAreAllReconciled() {
        Booking booking1 = org.mockito.Mockito.mock(Booking.class);
        Booking booking2 = org.mockito.Mockito.mock(Booking.class);

        when(booking1.getId()).thenReturn(101L);
        when(booking1.getReference()).thenReturn("AT-TEST-101");

        when(booking2.getId()).thenReturn(102L);
        when(booking2.getReference()).thenReturn("AT-TEST-102");

        PaymentResult result1 =
                PaymentResult.declined("declined");

        PaymentResult result2 =
                PaymentResult.notFound();

        when(bookingRepository.findUnresolvedPayments(
                any(Instant.class),
                any(Pageable.class)
        )).thenReturn(List.of(booking1, booking2));

        when(paymentGateway.lookup("AT-TEST-101"))
                .thenReturn(result1);

        when(paymentGateway.lookup("AT-TEST-102"))
                .thenReturn(result2);

        PaymentReconciler reconciler =
                new PaymentReconciler(
                        bookingRepository,
                        paymentGateway,
                        reconciliationService,
                        2,
                        50
                );

        reconciler.reconcileUnresolvedPayments();

        verify(paymentGateway)
                .lookup("AT-TEST-101");

        verify(paymentGateway)
                .lookup("AT-TEST-102");

        verify(reconciliationService)
                .applyOutcome(101L, result1);

        verify(reconciliationService)
                .applyOutcome(102L, result2);
    }
}