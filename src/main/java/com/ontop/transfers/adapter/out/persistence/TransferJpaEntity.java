package com.ontop.transfers.adapter.out.persistence;

import com.ontop.transfers.domain.model.ReviewReason;
import com.ontop.transfers.domain.model.TransferStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * {@code inFlightUserId} equals {@code userId} while the transfer is in an in-flight status and is null otherwise.
 * A unique constraint on it allows at most one in-flight transfer per user, portable across H2 and PostgreSQL.
 */
@Entity
@Table(name = "transfer")
class TransferJpaEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Column(name = "recipient_name", nullable = false)
    private String recipientName;

    @Column(name = "recipient_routing_number", nullable = false)
    private String recipientRoutingNumber;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "recipient_account_number", nullable = false)
    private String recipientAccountNumber;

    @Column(name = "recipient_bank_name", nullable = false)
    private String recipientBankName;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false)
    private String requestFingerprint;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private BigDecimal fee;

    @Column(name = "net_amount", nullable = false)
    private BigDecimal netAmount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransferStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_reason")
    private ReviewReason reviewReason;

    @Column(name = "in_flight_user_id")
    private Long inFlightUserId;

    @Column(name = "wallet_debit_id")
    private String walletDebitId;

    @Column(name = "wallet_refund_id")
    private String walletRefundId;

    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected TransferJpaEntity() {
        // for JPA
    }

    TransferJpaEntity(UUID id, long userId, UUID recipientId, String recipientName, String recipientRoutingNumber,
                      String recipientAccountNumber, String recipientBankName, String idempotencyKey,
                      String requestFingerprint, BigDecimal amount, BigDecimal fee, BigDecimal netAmount,
                      String currency, TransferStatus status, ReviewReason reviewReason, String walletDebitId,
                      String walletRefundId,
                      String providerPaymentId, String failureReason, Instant createdAt, Long version) {
        this.id = id;
        this.userId = userId;
        this.recipientId = recipientId;
        this.recipientName = recipientName;
        this.recipientRoutingNumber = recipientRoutingNumber;
        this.recipientAccountNumber = recipientAccountNumber;
        this.recipientBankName = recipientBankName;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.amount = amount;
        this.fee = fee;
        this.netAmount = netAmount;
        this.currency = currency;
        this.status = status;
        this.reviewReason = reviewReason;
        this.inFlightUserId = status.isInFlight() ? userId : null;
        this.walletDebitId = walletDebitId;
        this.walletRefundId = walletRefundId;
        this.providerPaymentId = providerPaymentId;
        this.failureReason = failureReason;
        this.createdAt = createdAt;
        this.version = version;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    UUID getId() {
        return id;
    }

    long getUserId() {
        return userId;
    }

    UUID getRecipientId() {
        return recipientId;
    }

    String getRecipientName() {
        return recipientName;
    }

    String getRecipientRoutingNumber() {
        return recipientRoutingNumber;
    }

    String getRecipientAccountNumber() {
        return recipientAccountNumber;
    }

    String getRecipientBankName() {
        return recipientBankName;
    }

    String getIdempotencyKey() {
        return idempotencyKey;
    }

    String getRequestFingerprint() {
        return requestFingerprint;
    }

    BigDecimal getAmount() {
        return amount;
    }

    BigDecimal getFee() {
        return fee;
    }

    BigDecimal getNetAmount() {
        return netAmount;
    }

    String getCurrency() {
        return currency;
    }

    TransferStatus getStatus() {
        return status;
    }

    ReviewReason getReviewReason() {
        return reviewReason;
    }

    String getWalletDebitId() {
        return walletDebitId;
    }

    String getWalletRefundId() {
        return walletRefundId;
    }

    String getProviderPaymentId() {
        return providerPaymentId;
    }

    String getFailureReason() {
        return failureReason;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Long getVersion() {
        return version;
    }
}
