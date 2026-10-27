package com.ontop.transfers.application.port.out;

import com.ontop.transfers.domain.model.TransferStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ReconciliationRepository {

    /**
     * Completed or refunded transfers whose {@code transfers_in_transit} postings do not net to zero against the
     * transfer amount: a missing or duplicated ledger entry.
     */
    List<UUID> findClosedTransfersWithUnbalancedTransit();

    /**
     * Non-terminal transfers that have not changed since {@code cutoff}, counted by status.
     */
    Map<TransferStatus, Long> countOpenTransfersNotUpdatedSince(Instant cutoff);
}
