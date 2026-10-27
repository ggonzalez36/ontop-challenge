package com.ontop.transfers.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only: never updated or deleted; corrections are new entries.
 */
@Entity
@Immutable
@Table(name = "ledger_entry")
class LedgerEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_id", nullable = false)
    private UUID transferId;

    @Column(nullable = false)
    private String account;

    @Column(nullable = false)
    private BigDecimal debit;

    @Column(nullable = false)
    private BigDecimal credit;

    @Column(nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntryEntity() {
        // for JPA
    }

    LedgerEntryEntity(UUID transferId, String account, BigDecimal debit, BigDecimal credit, String currency,
                      Instant createdAt) {
        this.transferId = transferId;
        this.account = account;
        this.debit = debit;
        this.credit = credit;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    String getAccount() {
        return account;
    }

    BigDecimal getDebit() {
        return debit;
    }

    BigDecimal getCredit() {
        return credit;
    }
}
