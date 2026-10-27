package com.ontop.transfers.domain.fee;

import com.ontop.transfers.domain.exception.InvalidAmountException;
import com.ontop.transfers.domain.model.Money;

/**
 * How an amount sent splits into fee and net amount. The quote and the transfer both use it, so the screen and the
 * execution can never disagree. Invariant: {@code amount = fee + netAmount}.
 */
public record FeeBreakdown(Money amount, Money fee, Money netAmount) {

    public static FeeBreakdown of(Money amount, FeePolicy feePolicy) {
        if (!amount.isPositive()) {
            throw new InvalidAmountException("Amount must be greater than zero");
        }
        Money fee = feePolicy.feeFor(amount);
        Money netAmount = amount.minus(fee);
        if (!netAmount.isPositive()) {
            throw new InvalidAmountException("Amount is too small to cover the transaction fee");
        }
        return new FeeBreakdown(amount, fee, netAmount);
    }
}
