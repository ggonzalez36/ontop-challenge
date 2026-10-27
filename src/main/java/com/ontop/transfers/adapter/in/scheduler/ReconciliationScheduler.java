package com.ontop.transfers.adapter.in.scheduler;

import com.ontop.transfers.application.port.in.ReconcileTransfersUseCase;
import com.ontop.transfers.application.port.in.ReconcileTransfersUseCase.ReconciliationReport;
import com.ontop.transfers.domain.model.TransferStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Daily reconciliation. Publishes its result as gauges ({@code ontop.reconciliation.unbalanced},
 * {@code ontop.transfers.stale{status}}) so alerts fire on any value above zero.
 */
@Component
@ConditionalOnProperty(name = "ontop.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class ReconciliationScheduler {

    private final ReconcileTransfersUseCase reconciliation;
    private final AtomicLong unbalanced = new AtomicLong();
    private final Map<TransferStatus, AtomicLong> stale = new EnumMap<>(TransferStatus.class);

    public ReconciliationScheduler(ReconcileTransfersUseCase reconciliation, MeterRegistry registry) {
        this.reconciliation = reconciliation;
        Gauge.builder("ontop.reconciliation.unbalanced", unbalanced, AtomicLong::get).register(registry);
        for (TransferStatus status : TransferStatus.recoverable()) {
            AtomicLong value = new AtomicLong();
            stale.put(status, value);
            Gauge.builder("ontop.transfers.stale", value, AtomicLong::get).tag("status", status.name())
                    .register(registry);
        }
    }

    @Scheduled(cron = "${ontop.reconciliation.cron}")
    @SchedulerLock(name = "transfer-reconciliation", lockAtMostFor = "PT30M")
    public void run() {
        ReconciliationReport report = reconciliation.reconcile();
        unbalanced.set(report.unbalancedTransfers().size());
        stale.forEach((status, value) -> value.set(report.staleOpenTransfers().getOrDefault(status, 0L)));
    }
}
