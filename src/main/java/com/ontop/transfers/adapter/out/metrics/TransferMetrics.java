package com.ontop.transfers.adapter.out.metrics;

import com.ontop.transfers.domain.event.TransferEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Counts transfer state changes ({@code ontop.transfers.events{type=...}}) after they are committed, so rolled-back
 * changes are never counted. Dashboards derive success, refund and review rates from these counters.
 */
@Component
public class TransferMetrics {

    static final String EVENTS = "ontop.transfers.events";

    private final MeterRegistry registry;

    public TransferMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(TransferEvent event) {
        registry.counter(EVENTS, "type", event.type()).increment();
    }
}
