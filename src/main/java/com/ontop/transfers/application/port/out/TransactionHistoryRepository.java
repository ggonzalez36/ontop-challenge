package com.ontop.transfers.application.port.out;

import com.ontop.transfers.domain.model.Money;
import com.ontop.transfers.domain.model.TransferStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model behind the "Recent transactions" screen: one line per wallet movement (withdrawal or refund), newest
 * first, with keyset pagination.
 */
public interface TransactionHistoryRepository {

    /**
     * @param after null for the first page
     */
    List<HistoryEntry> findPage(long userId, HistoryCursor after, int limit);

    enum MovementType {
        WITHDRAWAL,
        REFUND
    }

    record HistoryEntry(long id, UUID transferId, MovementType type, Money amount, TransferStatus transferStatus,
                        String recipientName, Instant occurredAt) {

        public HistoryCursor cursor() {
            return new HistoryCursor(occurredAt, id);
        }
    }

    record HistoryCursor(Instant occurredAt, long id) {
    }
}
