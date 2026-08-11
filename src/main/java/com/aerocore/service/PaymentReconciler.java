package com.aerocore.service;

import com.aerocore.model.Booking;
import com.aerocore.payment.PaymentGateway;
import com.aerocore.payment.PaymentGateway.PaymentResult;
import com.aerocore.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Finishes the job for bookings whose payment outcome was never learned.
 *
 * <p>A gateway timeout leaves a booking in PAYMENT_PENDING holding a seat, and the hold
 * sweeper deliberately refuses to touch it -- expiring a booking that might have been paid
 * for is the one mistake worth avoiding at almost any cost. So something has to actually ask
 * the question, and only the gateway can answer it.
 *
 * <p>Runs far less often than the sweeper because its working set is tiny. Abandoned holds
 * are common; charges with no answer are rare. That asymmetry is exactly why the two live in
 * separate jobs: the cheap guess handles the many, the expensive question handles the few.
 */
@Component
public class PaymentReconciler {

    private static final Logger log = LoggerFactory.getLogger(PaymentReconciler.class);

    private final BookingRepository bookingRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentReconciliationService reconciliationService;
    private final Duration graceperiod;
    private final int batchSize;

    public PaymentReconciler(BookingRepository bookingRepository,
                             PaymentGateway paymentGateway,
                             PaymentReconciliationService reconciliationService,
                             @Value("${aerocore.payments.reconcile-after-minutes:2}") long graceMinutes,
                             @Value("${aerocore.payments.reconcile-batch-size:50}") int batchSize) {
        this.bookingRepository = bookingRepository;
        this.paymentGateway = paymentGateway;
        this.reconciliationService = reconciliationService;
        this.graceperiod = Duration.ofMinutes(graceMinutes);
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${aerocore.payments.reconcile-interval-ms:300000}")
    public void reconcileUnresolvedPayments() {
        // The grace period keeps us out of the way of checkouts still in flight. A booking that
        // reached the gateway ninety seconds ago is probably mid-call, not stuck.
        Instant cutoff = Instant.now().minus(graceperiod);

        List<Booking> unresolved =
                bookingRepository.findUnresolvedPayments(cutoff, PageRequest.of(0, batchSize));

        if (unresolved.isEmpty()) {
            return;
        }

        log.info("Reconciling {} payment(s) with no recorded outcome", unresolved.size());

        for (Booking booking : unresolved) {
            // Outside any transaction, on purpose. This is a network call to someone else's
            // service, and holding a database transaction across it is the mistake checkout
            // exists to avoid -- a reconciler that empties the connection pool while tidying up
            // is worse than the mess it was tidying.
            PaymentResult result = paymentGateway.lookup(booking.getReference());

            reconciliationService.applyOutcome(booking.getId(), result);
        }
    }
}
