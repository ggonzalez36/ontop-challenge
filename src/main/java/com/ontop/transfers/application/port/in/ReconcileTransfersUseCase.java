package com.ontop.transfers.application.port.in;

import com.ontop.transfers.domain.model.TransferStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ReconcileTransfersUseCase {

    ReconciliationReport reconcile();

    /**
     * @param unbalancedTransfers closed transfers whose ledger does not balance
     * @param staleOpenTransfers  non-terminal transfers older than the threshold, by status
     */
    record ReconciliationReport(Instant runAt, List<UUID> unbalancedTransfers,
                                Map<TransferStatus, Long> staleOpenTransfers) {

        public boolean isClean() {
            return unbalancedTransfers.isEmpty() && staleOpenTransfers.isEmpty();
        }
    }
}
