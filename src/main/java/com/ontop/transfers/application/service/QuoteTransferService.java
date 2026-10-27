package com.ontop.transfers.application.service;

import com.ontop.transfers.application.port.in.QuoteTransferUseCase;
import com.ontop.transfers.application.port.out.WalletBalancePort;
import com.ontop.transfers.domain.fee.FeeBreakdown;
import com.ontop.transfers.domain.fee.FeePolicy;
import com.ontop.transfers.domain.model.Money;

public final class QuoteTransferService implements QuoteTransferUseCase {

    private final WalletBalancePort walletBalance;
    private final FeePolicy feePolicy;

    public QuoteTransferService(WalletBalancePort walletBalance, FeePolicy feePolicy) {
        this.walletBalance = walletBalance;
        this.feePolicy = feePolicy;
    }

    @Override
    public TransferQuote quote(long userId, Money amount) {
        FeeBreakdown breakdown = FeeBreakdown.of(amount, feePolicy);
        return new TransferQuote(breakdown, walletBalance.getBalance(userId));
    }
}
