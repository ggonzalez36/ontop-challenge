package com.ontop.transfers.domain.ledger;

import com.ontop.transfers.domain.event.TransferEvent;
import com.ontop.transfers.domain.model.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Translates transfer events into balanced double-entry postings (ADR 005). Wallet balances are liabilities of
 * Ontop: debiting {@code user_wallet} lowers what Ontop owes the user, crediting {@code ontop_bank} records money
 * leaving Ontop's bank account. {@code transfers_in_transit} must return to zero for every closed transfer.
 */
public final class LedgerPostings {

    public static final String IN_TRANSIT = "transfers_in_transit";
    public static final String ONTOP_BANK = "ontop_bank";
    public static final String FEE_REVENUE = "fee_revenue";
    private static final String USER_WALLET_PREFIX = "user_wallet:";

    private LedgerPostings() {
    }

    public static String userWallet(long userId) {
        return USER_WALLET_PREFIX + userId;
    }

    public static List<LedgerEntry> forEvent(TransferEvent event) {
        return switch (event) {
            case TransferEvent.WalletDebited e -> List.of(
                    LedgerEntry.debit(e.transferId(), userWallet(e.userId()), e.amount()),
                    LedgerEntry.credit(e.transferId(), IN_TRANSIT, e.amount()));
            case TransferEvent.TransferCompleted e -> completed(e.transferId(), e.amount(), e.fee(), e.netAmount());
            case TransferEvent.TransferRefunded e -> List.of(
                    LedgerEntry.debit(e.transferId(), IN_TRANSIT, e.amount()),
                    LedgerEntry.credit(e.transferId(), userWallet(e.userId()), e.amount()));
            case TransferEvent.TransferCreated e -> List.of();
            case TransferEvent.PaymentSubmitted e -> List.of();
            case TransferEvent.PaymentFailed e -> List.of();
            case TransferEvent.TransferSentToReview e -> List.of();
            case TransferEvent.TransferRejected e -> List.of();
        };
    }

    private static List<LedgerEntry> completed(UUID transferId, Money amount, Money fee, Money netAmount) {
        List<LedgerEntry> entries = new ArrayList<>();
        entries.add(LedgerEntry.debit(transferId, IN_TRANSIT, amount));
        entries.add(LedgerEntry.credit(transferId, ONTOP_BANK, netAmount));
        if (fee.isPositive()) {
            entries.add(LedgerEntry.credit(transferId, FEE_REVENUE, fee));
        }
        return entries;
    }
}
