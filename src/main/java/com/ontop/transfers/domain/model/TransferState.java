package com.ontop.transfers.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Full state of a {@link Transfer}, used only to rehydrate it from storage.
 */
public record TransferState(
        UUID id,
        long userId,
        UUID recipientId,
        RecipientSnapshot recipient,
        Money amount,
        Money fee,
        Money netAmount,
        IdempotencyKey idempotencyKey,
        TransferStatus status,
        ReviewReason reviewReason,
        String walletDebitId,
        String walletRefundId,
        String providerPaymentId,
        String failureReason,
        Instant createdAt,
        Long version) {
}
