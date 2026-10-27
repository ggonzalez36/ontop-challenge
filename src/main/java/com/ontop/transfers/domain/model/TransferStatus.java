package com.ontop.transfers.domain.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of a transfer. See docs/architecture-design.md, section 7.
 */
public enum TransferStatus {
    CREATED,
    WALLET_DEBITED,
    PROCESSING,
    COMPLETED,
    PAYMENT_FAILED,
    REFUNDED,
    IN_REVIEW,
    REJECTED;

    private static final Map<TransferStatus, Set<TransferStatus>> ALLOWED = new EnumMap<>(TransferStatus.class);

    static {
        ALLOWED.put(CREATED, EnumSet.of(WALLET_DEBITED, REJECTED, IN_REVIEW));
        ALLOWED.put(WALLET_DEBITED, EnumSet.of(PROCESSING, PAYMENT_FAILED, IN_REVIEW));
        ALLOWED.put(PROCESSING, EnumSet.of(COMPLETED, PAYMENT_FAILED));
        ALLOWED.put(IN_REVIEW, EnumSet.of(PROCESSING, PAYMENT_FAILED, REJECTED));
        ALLOWED.put(PAYMENT_FAILED, EnumSet.of(REFUNDED, IN_REVIEW));
        ALLOWED.put(COMPLETED, EnumSet.noneOf(TransferStatus.class));
        ALLOWED.put(REFUNDED, EnumSet.noneOf(TransferStatus.class));
        ALLOWED.put(REJECTED, EnumSet.noneOf(TransferStatus.class));
    }

    public boolean canTransitionTo(TransferStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isTerminal() {
        return ALLOWED.get(this).isEmpty();
    }

    /**
     * States between "decided to debit" and "wallet confirmed the debit". Only one transfer per user may be here.
     */
    public boolean isInFlight() {
        return this == CREATED || this == WALLET_DEBITED;
    }

    /**
     * Non-terminal states the recovery job looks at when a transfer has not moved for a while.
     */
    public static Set<TransferStatus> recoverable() {
        return EnumSet.of(CREATED, WALLET_DEBITED, PROCESSING, PAYMENT_FAILED, IN_REVIEW);
    }
}
