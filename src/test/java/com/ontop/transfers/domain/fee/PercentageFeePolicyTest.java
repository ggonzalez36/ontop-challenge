package com.ontop.transfers.domain.fee;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static com.ontop.transfers.TestData.TEN_PERCENT;
import static com.ontop.transfers.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PercentageFeePolicyTest {

    @ParameterizedTest(name = "{0} -> fee {1}")
    @CsvSource({
            "20.00, 2.00",      // the example on the "How much money" screen
            "33.35, 3.34",      // 3.335 rounds half to even -> 3.34
            "33.25, 3.32",      // 3.325 rounds half to even -> 3.32
            "0.05, 0.00",
            "99924.00, 9992.40"
    })
    void computesTenPercentRoundedHalfEven(String amount, String expectedFee) {
        assertThat(TEN_PERCENT.feeFor(usd(amount))).isEqualTo(usd(expectedFee));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1", "1.5"})
    void rejectsRatesOutsideZeroToOne(String rate) {
        BigDecimal invalidRate = new BigDecimal(rate);
        assertThatThrownBy(() -> new PercentageFeePolicy(invalidRate))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
