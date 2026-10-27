package com.ontop.transfers.domain.model;

import com.ontop.transfers.domain.exception.InvalidAmountException;
import org.junit.jupiter.api.Test;

import static com.ontop.transfers.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void normalizesToTwoDecimals() {
        assertThat(usd("20").amount()).hasToString("20.00");
        assertThat(usd("20.5")).isEqualTo(usd("20.50"));
    }

    @Test
    void rejectsMoreThanTwoDecimals() {
        assertThatThrownBy(() -> usd("10.001"))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("at most 2 decimals");
    }

    @Test
    void acceptsTrailingZerosBeyondTwoDecimals() {
        assertThat(usd("10.000").amount()).hasToString("10.00");
    }

    @Test
    void rejectsUnknownCurrency() {
        assertThatThrownBy(() -> Money.of("10", "XXZ"))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("Unsupported currency");
    }

    @Test
    void rejectsArithmeticAcrossCurrencies() {
        assertThatThrownBy(() -> usd("10").minus(Money.of("1", "EUR")))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("Currency mismatch");
    }

    @Test
    void comparesAndSubtracts() {
        assertThat(usd("20").minus(usd("2"))).isEqualTo(usd("18"));
        assertThat(usd("19.99").isLessThan(usd("20"))).isTrue();
        assertThat(usd("0").isPositive()).isFalse();
        assertThat(usd("5").negate()).isEqualTo(usd("-5"));
    }
}
