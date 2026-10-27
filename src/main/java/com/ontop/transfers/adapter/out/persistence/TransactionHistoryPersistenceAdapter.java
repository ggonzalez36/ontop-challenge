package com.ontop.transfers.adapter.out.persistence;

import com.ontop.transfers.adapter.out.persistence.SpringDataWalletMovementRepository.HistoryRow;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository;
import com.ontop.transfers.domain.model.Money;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class TransactionHistoryPersistenceAdapter implements TransactionHistoryRepository {

    private final SpringDataWalletMovementRepository movements;

    TransactionHistoryPersistenceAdapter(SpringDataWalletMovementRepository movements) {
        this.movements = movements;
    }

    @Override
    public List<HistoryEntry> findPage(long userId, HistoryCursor after, int limit) {
        List<HistoryRow> rows = after == null
                ? movements.firstPage(userId, Limit.of(limit))
                : movements.pageAfter(userId, after.occurredAt(), after.id(), Limit.of(limit));
        return rows.stream().map(TransactionHistoryPersistenceAdapter::toEntry).toList();
    }

    private static HistoryEntry toEntry(HistoryRow row) {
        return new HistoryEntry(row.getId(), row.getTransferId(), row.getType(),
                Money.of(row.getAmount(), row.getCurrency()), row.getTransferStatus(), row.getRecipientName(),
                row.getOccurredAt());
    }
}
