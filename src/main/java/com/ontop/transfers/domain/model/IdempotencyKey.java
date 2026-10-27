package com.ontop.transfers.domain.model;

import java.util.Objects;

/**
 * Client-provided key plus a fingerprint of the request it was first used with.
 */
public record IdempotencyKey(String value, String requestFingerprint) {

    public IdempotencyKey {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
    }

    public boolean matches(String fingerprint) {
        return requestFingerprint.equals(fingerprint);
    }
}
