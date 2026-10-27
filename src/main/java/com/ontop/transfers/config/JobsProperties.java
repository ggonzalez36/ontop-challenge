package com.ontop.transfers.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param stuckAfter    how long a non-terminal transfer may stay unchanged before the recovery job looks at it
 *                      (also the grace period before "payment not found" is trusted)
 * @param batchSize     transfers per recovery run
 * @param openTooLong   reconciliation flags non-terminal transfers older than this
 */
@Validated
@ConfigurationProperties("ontop.recovery")
public record JobsProperties(
        @NotNull Duration stuckAfter,
        @Min(1) @Max(1000) int batchSize,
        @NotNull Duration openTooLong) {
}
