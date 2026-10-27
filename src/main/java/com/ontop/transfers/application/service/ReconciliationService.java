package com.ontop.transfers.application.service;

import com.ontop.transfers.application.port.in.ReconcileTransfersUseCase;
import com.ontop.transfers.application.port.out.ReconciliationRepository;
import com.ontop.transfers.domain.model.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal side of the reconciliation (ADR 005): the ledger must balance for every closed transfer and nothing may
 * stay open for too long. Comparing against the wallet's and the provider's statements needs APIs the mocks do not
 * offer; see docs/open-questions.md.
 */
public final class ReconciliationService implements ReconcileTransfersUseCase {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final ReconciliationRepository reconciliation;
    private final Clock clock;
    private final Duration openTooLong;

    public ReconciliationService(ReconciliationRepository reconciliation, Clock clock, Duration openTooLong) {
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.openTooLong = openTooLong;
    }

    @Override
    public ReconciliationReport reconcile() {
        Instant now = clock.instant();
        List<UUID> unbalanced = reconciliation.findClosedTransfersWithUnbalancedTransit();
        Map<TransferStatus, Long> stale = reconciliation.countOpenTransfersNotUpdatedSince(now.minus(openTooLong));
        ReconciliationReport report = new ReconciliationReport(now, List.copyOf(unbalanced), Map.copyOf(stale));
        if (report.isClean()) {
            log.info("Reconciliation clean");
        } else {
            log.warn("Reconciliation found issues: unbalanced={} staleOpen={}", unbalanced, stale);
        }
        return report;
    }
}
