package com.ontop.transfers.domain.fee;

import com.ontop.transfers.domain.model.Money;

/**
 * Pricing rule for withdrawals. New pricing (caps, tiers, promotions) is a new implementation, not a change to the
 * transfer flow.
 */
public interface FeePolicy {

    Money feeFor(Money amount);
}
