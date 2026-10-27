package com.ontop.transfers.application.service;

import com.ontop.transfers.application.exception.InvalidCursorException;
import com.ontop.transfers.application.port.in.GetTransactionHistoryUseCase;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository.HistoryCursor;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository.HistoryEntry;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Keyset pagination: the cursor is the (occurredAt, id) of the last item, so pages stay stable while new movements
 * arrive, unlike offset pagination.
 */
public final class TransactionHistoryService implements GetTransactionHistoryUseCase {

    private final TransactionHistoryRepository history;

    public TransactionHistoryService(TransactionHistoryRepository history) {
        this.history = history;
    }

    @Override
    public HistoryPage history(long userId, String cursor, int limit) {
        int pageSize = Math.clamp(limit, 1, MAX_PAGE_SIZE);
        HistoryCursor after = cursor == null || cursor.isBlank() ? null : decode(cursor);
        List<HistoryEntry> rows = history.findPage(userId, after, pageSize + 1);
        if (rows.size() <= pageSize) {
            return new HistoryPage(rows, null);
        }
        List<HistoryEntry> page = rows.subList(0, pageSize);
        return new HistoryPage(List.copyOf(page), encode(page.getLast().cursor()));
    }

    static String encode(HistoryCursor cursor) {
        String raw = cursor.occurredAt().toString() + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static HistoryCursor decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            return new HistoryCursor(Instant.parse(raw.substring(0, separator)), Long.parseLong(raw.substring(separator + 1)));
        } catch (RuntimeException e) {
            throw new InvalidCursorException(cursor);
        }
    }
}
