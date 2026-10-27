package com.ontop.transfers.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Validated
@ConfigurationProperties("ontop.fees")
public record FeeProperties(
        @NotNull @DecimalMin("0") @DecimalMax(value = "1", inclusive = false) BigDecimal withdrawalRate) {
}
