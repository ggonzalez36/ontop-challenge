package com.ontop.transfers.adapter.out.payments;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param source        Ontop's bank account. Every payment goes out from this account; it is never taken from a
 *                      request.
 * @param lookupEnabled whether the provider offers "get payment by reference". The mocks do not, so it is off by
 *                      default and the recovery job leaves unknown payments for manual review.
 */
@Validated
@ConfigurationProperties("ontop.payments")
public record PaymentsProperties(
        @NotBlank String baseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        boolean lookupEnabled,
        @Valid @NotNull SourceAccount source) {

    public record SourceAccount(
            @NotBlank String name,
            @NotBlank String accountNumber,
            @NotBlank String routingNumber,
            @NotBlank String currency) {
    }
}
