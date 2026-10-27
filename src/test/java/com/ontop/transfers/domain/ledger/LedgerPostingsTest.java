package com.ontop.transfers.domain.ledger;

import com.ontop.transfers.domain.event.TransferEvent;
import com.ontop.transfers.domain.model.Money;
import com.ontop.transfers.domain.model.Transfer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static com.ontop.transfers.TestData.newTransfer;
import static com.ontop.transfers.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;

class LedgerPostingsTest {

    private static List<LedgerEntry> post(List<TransferEvent> events) {
        List<LedgerEntry> entries = new ArrayList<>();
        events.forEach(event -> entries.addAll(LedgerPostings.forEvent(event)));
        return entries;
    }

    private static BigDecimal sum(List<LedgerEntry> entries, String account, boolean debit) {
        return entries.stream().filter(e -> e.account().equals(account))
                .map(e -> debit ? e.debit() : e.credit()).map(Money::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void assertBalanced(List<LedgerEntry> entries) {
        BigDecimal debits = entries.stream().map(e -> e.debit().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = entries.stream().map(e -> e.credit().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debits).isEqualByComparingTo(credits);
    }

    @Test
    void completedTransferSplitsAmountIntoBankAndFeeAndClosesTransit() {
        Transfer transfer = newTransfer("20.00");
        transfer.markWalletDebited("w-1");
        transfer.markPaymentSubmitted("p-1");
        transfer.markCompleted();

        List<LedgerEntry> entries = post(transfer.pullEvents());

        assertBalanced(entries);
        String wallet = LedgerPostings.userWallet(transfer.userId());
        assertThat(sum(entries, wallet, true)).isEqualByComparingTo("20.00");
        assertThat(sum(entries, LedgerPostings.ONTOP_BANK, false)).isEqualByComparingTo("18.00");
        assertThat(sum(entries, LedgerPostings.FEE_REVENUE, false)).isEqualByComparingTo("2.00");
        assertThat(sum(entries, LedgerPostings.IN_TRANSIT, true))
                .isEqualByComparingTo(sum(entries, LedgerPostings.IN_TRANSIT, false));
    }

    @Test
    void refundedTransferGivesTheFullAmountBackAndEarnsNoFee() {
        Transfer transfer = newTransfer("20.00");
        transfer.markWalletDebited("w-1");
        transfer.markPaymentFailed("bank rejected");
        transfer.markRefunded("w-2");

        List<LedgerEntry> entries = post(transfer.pullEvents());

        assertBalanced(entries);
        String wallet = LedgerPostings.userWallet(transfer.userId());
        assertThat(sum(entries, wallet, false)).isEqualByComparingTo(sum(entries, wallet, true));
        assertThat(sum(entries, LedgerPostings.FEE_REVENUE, false)).isEqualByComparingTo("0");
    }

    @Test
    void zeroFeeIsNotPosted() {
        List<LedgerEntry> entries = LedgerPostings.forEvent(new TransferEvent.TransferCompleted(
                newTransfer("0.05").id(), 1000, usd("0.05"), usd("0.00"), usd("0.05")));

        assertThat(entries).noneMatch(e -> e.account().equals(LedgerPostings.FEE_REVENUE));
        assertBalanced(entries);
    }
}
