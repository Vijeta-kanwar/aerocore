package com.aerocore.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A payment provider that does nothing except behave badly on request.
 *
 * <p>Integrating a real gateway would prove the happy path and almost nothing else. What the
 * design actually needs testing against is latency, declines and timeouts -- and a real
 * provider hands those out on its own schedule, if ever. Here they're properties: turn the
 * latency up to four seconds and watch what a transaction boundary in the wrong place does
 * to the rest of the application.
 *
 * <p>Charges are remembered in memory, keyed by booking reference, which is what makes the
 * idempotency promise real: charging the same reference twice returns the first charge
 * instead of taking the money again.
 */
@Component
public class StubPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StubPaymentGateway.class);

    private final Map<String, PaymentResult> charges = new ConcurrentHashMap<>();

    private final long latencyMs;
    private final double declineRate;
    private final double timeoutRate;

    public StubPaymentGateway(@Value("${aerocore.payments.latency-ms:1200}") long latencyMs,
                              @Value("${aerocore.payments.decline-rate:0.0}") double declineRate,
                              @Value("${aerocore.payments.timeout-rate:0.0}") double timeoutRate) {
        this.latencyMs = latencyMs;
        this.declineRate = declineRate;
        this.timeoutRate = timeoutRate;
    }

    @Override
    public PaymentResult charge(String bookingReference, BigDecimal amount) {
        // Idempotent by reference, exactly as a real provider would be. A retry after a
        // timeout finds the original charge rather than creating a second one.
        PaymentResult existing = charges.get(bookingReference);
        if (existing != null) {
            log.info("Replaying existing charge for {}", bookingReference);
            return existing;
        }

        sleep(latencyMs);

        if (roll() < timeoutRate) {
            // Deliberately not recorded either way. This is the nastiest real-world case: the
            // charge may or may not have gone through, and the caller genuinely cannot tell.
            log.warn("Charge for {} timed out with no answer", bookingReference);
            return PaymentResult.unknown("The payment provider did not respond in time");
        }

        if (roll() < declineRate) {
            PaymentResult declined = PaymentResult.declined("Card was declined by the issuer");
            charges.put(bookingReference, declined);
            return declined;
        }

        PaymentResult succeeded = PaymentResult.succeeded("ch_" + bookingReference.toLowerCase());
        charges.put(bookingReference, succeeded);
        log.info("Charged {} for reference {}", amount, bookingReference);
        return succeeded;
    }

    @Override
    public PaymentResult lookup(String bookingReference) {
        sleep(latencyMs / 4);
        return charges.getOrDefault(bookingReference, PaymentResult.notFound());
    }

    private double roll() {
        return ThreadLocalRandom.current().nextDouble();
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while talking to the payment provider", e);
        }
    }
}
