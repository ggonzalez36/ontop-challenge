package com.ontop.transfers.adapter.out.persistence;

import com.ontop.transfers.application.port.out.ReconciliationRepository;
import com.ontop.transfers.domain.ledger.LedgerPostings;
import com.ontop.transfers.domain.model.TransferStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
class ReconciliationPersistenceAdapter implements ReconciliationRepository {

    private final SpringDataTransferRepository transfers;

    ReconciliationPersistenceAdapter(SpringDataTransferRepository transfers) {
        this.transfers = transfers;
    }

    @Override
    public List<UUID> findClosedTransfersWithUnbalancedTransit() {
        return transfers.findClosedWithUnbalancedTransit(
                EnumSet.of(TransferStatus.COMPLETED, TransferStatus.REFUNDED), LedgerPostings.IN_TRANSIT);
    }

    @Override
    public Map<TransferStatus, Long> countOpenTransfersNotUpdatedSince(Instant cutoff) {
        Map<TransferStatus, Long> counts = new EnumMap<>(TransferStatus.class);
        transfers.countByStatusNotUpdatedSince(TransferStatus.recoverable(), cutoff)
                .forEach(row -> counts.put(row.getStatus(), row.getTotal()));
        return counts;
    }
}
