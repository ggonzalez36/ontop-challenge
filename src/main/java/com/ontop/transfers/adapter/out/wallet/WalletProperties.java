package com.ontop.transfers.adapter.out.wallet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("ontop.wallet")
public record WalletProperties(
        @NotBlank String baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @NotBlank String currency) {
}
