package com.ontop.transfers.application.port.in;

import com.ontop.transfers.domain.fee.FeeBreakdown;
import com.ontop.transfers.domain.model.Money;

/**
 * Powers the "How much money do you want to send?" screen: fee, net amount and available funds.
 */
public interface QuoteTransferUseCase {

    TransferQuote quote(long userId, Money amount);

    record TransferQuote(FeeBreakdown breakdown, Money availableBalance) {

        public boolean affordable() {
            return !availableBalance.isLessThan(breakdown.amount());
        }
    }
}
