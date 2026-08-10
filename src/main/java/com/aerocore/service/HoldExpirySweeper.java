package com.aerocore.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Wakes up periodically and asks {@link HoldExpiryService} to clear dead holds.
 *
 * <p>Split from the service for the same reason the idempotency beans are split: this
 * method isn't transactional and the one it calls is. A @Transactional method invoked from
 * its own class gets no transaction at all, because Spring's proxy only intercepts calls
 * arriving from outside the bean. Two beans makes that impossible to get wrong by accident.
 *
 * <p>All three replicas run this on their own timer, which is fine and deliberate -- the
 * claim query's SKIP LOCKED means they take different batches rather than fighting over the
 * same one. No leader election, no distributed lock, no coordination of any kind. The
 * database is already the thing they agree on.
 */
@Component
public class HoldExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldExpirySweeper.class);

    private final HoldExpiryService holdExpiryService;
    private final int batchSize;
    private final int maxBatchesPerRun;

    public HoldExpirySweeper(HoldExpiryService holdExpiryService,
                             @Value("${aerocore.holds.batch-size:100}") int batchSize,
                             @Value("${aerocore.holds.max-batches-per-run:20}") int maxBatchesPerRun) {
        this.holdExpiryService = holdExpiryService;
        this.batchSize = batchSize;
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    @Scheduled(fixedDelayString = "${aerocore.holds.sweep-interval-ms:60000}")
    public void sweepExpiredHolds() {
        int swept = 0;

        // Bounded on purpose. An unbounded loop during a backlog would hold a thread and a
        // connection indefinitely, and a sweeper that never finishes is indistinguishable
        // from one that's hung. Leftovers get picked up next tick.
        for (int batch = 0; batch < maxBatchesPerRun; batch++) {
            int expired = holdExpiryService.expireBatch(batchSize);
            swept += expired;
            if (expired < batchSize) {
                break;   // partial batch means we've drained what was due
            }
        }

        if (swept > 0) {
            log.info("Hold sweep released {} expired booking(s)", swept);
        }
    }
}
