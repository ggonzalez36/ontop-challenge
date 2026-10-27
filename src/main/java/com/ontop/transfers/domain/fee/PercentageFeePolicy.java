package com.ontop.transfers.domain.fee;

import com.ontop.transfers.domain.model.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Fee as a percentage of the amount sent, rounded to the cent with banker's rounding (HALF_EVEN) to avoid a
 * systematic bias across many transfers.
 */
public final class PercentageFeePolicy implements FeePolicy {

    private final BigDecimal rate;

    public PercentageFeePolicy(BigDecimal rate) {
        Objects.requireNonNull(rate, "rate");
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("Fee rate must be in [0, 1): " + rate);
        }
        this.rate = rate;
    }

    @Override
    public Money feeFor(Money amount) {
        return amount.multiply(rate, RoundingMode.HALF_EVEN);
    }
}
