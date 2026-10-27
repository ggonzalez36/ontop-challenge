package com.ontop.transfers.adapter.in.scheduler;

import com.ontop.transfers.application.port.in.RecoverStuckTransfersUseCase;
import com.ontop.transfers.application.port.in.RecoverStuckTransfersUseCase.RecoveryReport;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the recovery job periodically. ShedLock guarantees a single instance runs it at a time; optimistic locking
 * protects against the request thread or a provider callback touching the same transfer.
 */
@Component
@ConditionalOnProperty(name = "ontop.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class RecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);

    private final RecoverStuckTransfersUseCase recovery;

    public RecoveryScheduler(RecoverStuckTransfersUseCase recovery) {
        this.recovery = recovery;
    }

    @Scheduled(fixedDelayString = "${ontop.recovery.interval}")
    @SchedulerLock(name = "transfer-recovery", lockAtMostFor = "PT10M")
    public void run() {
        RecoveryReport report = recovery.recover();
        if (report.examined() > 0) {
            log.info("Recovery run: {}", report);
        }
    }
}
