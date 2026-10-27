package com.ontop.transfers.domain.ledger;

import com.ontop.transfers.domain.model.Money;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of a double-entry posting. Exactly one of {@code debit} or {@code credit} is non-zero.
 */
public record LedgerEntry(UUID transferId, String account, Money debit, Money credit) {

    public static LedgerEntry debit(UUID transferId, String account, Money amount) {
        return new LedgerEntry(transferId, account, amount, zero(amount));
    }

    public static LedgerEntry credit(UUID transferId, String account, Money amount) {
        return new LedgerEntry(transferId, account, zero(amount), amount);
    }

    private static Money zero(Money like) {
        return Money.of(BigDecimal.ZERO, like.currency());
    }
}
