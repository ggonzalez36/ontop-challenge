package com.ontop.transfers.adapter.in.web;

import com.ontop.transfers.application.port.in.GetTransactionHistoryUseCase;
import com.ontop.transfers.application.port.in.GetTransactionHistoryUseCase.HistoryPage;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository.HistoryEntry;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository.MovementType;
import com.ontop.transfers.domain.model.TransferStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * "Recent transactions" screen. Texts match the mockup; the client can localize by {@code type} and
 * {@code displayStatus}.
 */
@RestController
@RequestMapping("/v1/transactions")
public class TransactionHistoryController {

    private final GetTransactionHistoryUseCase history;

    public TransactionHistoryController(GetTransactionHistoryUseCase history) {
        this.history = history;
    }

    @GetMapping
    public HistoryResponse list(@AuthenticationPrincipal Jwt jwt,
                                @RequestParam(required = false) String cursor,
                                @RequestParam(defaultValue = "20") int limit) {
        HistoryPage page = history.history(CurrentUser.id(jwt), cursor, limit);
        return new HistoryResponse(page.items().stream().map(HistoryItem::from).toList(), page.nextCursor());
    }

    public record HistoryResponse(List<HistoryItem> items, String nextCursor) {
    }

    public record HistoryItem(UUID transferId, String type, String description, String displayStatus,
                              BigDecimal amount, String currency, String recipientName, Instant occurredAt) {

        static HistoryItem from(HistoryEntry entry) {
            boolean refund = entry.type() == MovementType.REFUND;
            return new HistoryItem(entry.transferId(), entry.type().name(),
                    refund ? "Refund from bank transfer" : "Bank transfer to your account",
                    refund ? "Refunded to your wallet" : withdrawalStatus(entry.transferStatus()),
                    entry.amount().amount(), entry.amount().currency().getCurrencyCode(), entry.recipientName(),
                    entry.occurredAt());
        }

        private static String withdrawalStatus(TransferStatus status) {
            return switch (status) {
                case COMPLETED -> "Transaction completed";
                case REFUNDED -> "Transaction failed";
                case REJECTED -> "Transaction rejected";
                case CREATED, WALLET_DEBITED, PROCESSING, PAYMENT_FAILED, IN_REVIEW -> "Transaction pending";
            };
        }
    }
}
