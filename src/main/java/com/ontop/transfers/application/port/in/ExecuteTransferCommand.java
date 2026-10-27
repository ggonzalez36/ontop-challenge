package com.ontop.transfers.application.port.in;

import com.ontop.transfers.domain.model.Money;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

public record ExecuteTransferCommand(long userId, UUID recipientId, Money amount, String idempotencyKey) {

    public ExecuteTransferCommand {
        Objects.requireNonNull(recipientId, "recipientId");
        Objects.requireNonNull(amount, "amount");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }

    /**
     * SHA-256 of the fields that define the transfer, used to detect an Idempotency-Key reused with another body.
     */
    public String fingerprint() {
        String canonical = recipientId + "|" + amount.amount().toPlainString() + "|" + amount.currency().getCurrencyCode();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
