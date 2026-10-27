package com.ontop.transfers.adapter.out.persistence;

import com.ontop.transfers.application.port.out.TransactionHistoryRepository.MovementType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * History read model: one row per movement of the user's wallet caused by a transfer.
 */
@Entity
@Immutable
@Table(name = "wallet_movement")
class WalletMovementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MovementType type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected WalletMovementEntity() {
        // for JPA
    }

    WalletMovementEntity(long userId, UUID transferId, MovementType type, BigDecimal amount, String currency,
                         Instant occurredAt) {
        this.userId = userId;
        this.transferId = transferId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.occurredAt = occurredAt;
    }
}
