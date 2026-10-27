package com.ontop.transfers.application.port.in;

import com.ontop.transfers.application.port.out.TransactionHistoryRepository.HistoryEntry;

import java.util.List;

public interface GetTransactionHistoryUseCase {

    int MAX_PAGE_SIZE = 50;

    /**
     * @param cursor opaque value from a previous page's {@code nextCursor}, or null for the first page
     */
    HistoryPage history(long userId, String cursor, int limit);

    /**
     * @param nextCursor null when there are no more pages
     */
    record HistoryPage(List<HistoryEntry> items, String nextCursor) {
    }
}
