package com.ontop.transfers.domain.model;

import com.ontop.transfers.domain.exception.IllegalTransferTransitionException;
import com.ontop.transfers.domain.exception.InsufficientFundsException;
import com.ontop.transfers.domain.exception.InvalidAmountException;
import com.ontop.transfers.domain.fee.FeePolicy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root for a wallet-to-bank withdrawal. Every status change goes through a method that validates the
 * transition; there are no setters.
 */
public final class Transfer {

    private final UUID id;
    private final long userId;
    private final UUID recipientId;
    private final RecipientSnapshot recipient;
    private final Money amount;
    private final Money fee;
    private final Money netAmount;
    private final IdempotencyKey idempotencyKey;
    private final Instant createdAt;
    private final Long version;

    private TransferStatus status;
    private String walletDebitId;
    private String walletRefundId;
    private String providerPaymentId;
    private String failureReason;

    private Transfer(TransferState state) {
        this.id = Objects.requireNonNull(state.id(), "id");
        this.userId = state.userId();
        this.recipientId = Objects.requireNonNull(state.recipientId(), "recipientId");
        this.recipient = Objects.requireNonNull(state.recipient(), "recipient");
        this.amount = Objects.requireNonNull(state.amount(), "amount");
        this.fee = Objects.requireNonNull(state.fee(), "fee");
        this.netAmount = Objects.requireNonNull(state.netAmount(), "netAmount");
        this.idempotencyKey = Objects.requireNonNull(state.idempotencyKey(), "idempotencyKey");
        this.status = Objects.requireNonNull(state.status(), "status");
        this.walletDebitId = state.walletDebitId();
        this.walletRefundId = state.walletRefundId();
        this.providerPaymentId = state.providerPaymentId();
        this.failureReason = state.failureReason();
        this.createdAt = Objects.requireNonNull(state.createdAt(), "createdAt");
        this.version = state.version();
    }

    /**
     * Validates the business rules of a new withdrawal and computes its fee. The fee is deducted from the amount
     * sent: the wallet is debited {@code amount} and the recipient gets {@code amount - fee}.
     */
    public static Transfer create(long userId, Recipient recipient, Money amount, Money availableBalance,
                                  FeePolicy feePolicy, IdempotencyKey idempotencyKey, Instant now) {
        if (!amount.isPositive()) {
            throw new InvalidAmountException("Amount must be greater than zero");
        }
        if (!amount.currency().equals(recipient.bankAccount().currency())) {
            throw new InvalidAmountException("Currency " + amount.currency() + " is not supported by this recipient");
        }
        Money fee = feePolicy.feeFor(amount);
        Money netAmount = amount.minus(fee);
        if (!netAmount.isPositive()) {
            throw new InvalidAmountException("Amount is too small to cover the transaction fee");
        }
        if (availableBalance.isLessThan(amount)) {
            throw new InsufficientFundsException(amount, availableBalance);
        }
        return new Transfer(new TransferState(UUID.randomUUID(), userId, recipient.id(), recipient.snapshot(),
                amount, fee, netAmount, idempotencyKey, TransferStatus.CREATED, null, null, null, null, now, null));
    }

    public static Transfer restore(TransferState state) {
        return new Transfer(state);
    }

    public void markWalletDebited(String walletTransactionId) {
        moveTo(TransferStatus.WALLET_DEBITED);
        this.walletDebitId = requireText(walletTransactionId, "walletTransactionId");
    }

    public void markPaymentSubmitted(String paymentId) {
        moveTo(TransferStatus.PROCESSING);
        this.providerPaymentId = requireText(paymentId, "paymentId");
    }

    public void markCompleted() {
        moveTo(TransferStatus.COMPLETED);
    }

    public void markPaymentFailed(String reason) {
        moveTo(TransferStatus.PAYMENT_FAILED);
        this.failureReason = reason;
    }

    /**
     * A remote call timed out or failed ambiguously: money may or may not have moved. Never refunded automatically.
     */
    public void markOutcomeUnknown(String reason) {
        moveTo(TransferStatus.IN_REVIEW);
        this.failureReason = reason;
    }

    public void markRefunded(String walletTransactionId) {
        moveTo(TransferStatus.REFUNDED);
        this.walletRefundId = requireText(walletTransactionId, "walletTransactionId");
    }

    public void reject(String reason) {
        moveTo(TransferStatus.REJECTED);
        this.failureReason = reason;
    }

    private void moveTo(TransferStatus next) {
        if (!status.canTransitionTo(next)) {
            throw new IllegalTransferTransitionException(id, status, next);
        }
        this.status = next;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public UUID id() {
        return id;
    }

    public long userId() {
        return userId;
    }

    public UUID recipientId() {
        return recipientId;
    }

    public RecipientSnapshot recipient() {
        return recipient;
    }

    public Money amount() {
        return amount;
    }

    public Money fee() {
        return fee;
    }

    public Money netAmount() {
        return netAmount;
    }

    public IdempotencyKey idempotencyKey() {
        return idempotencyKey;
    }

    public TransferStatus status() {
        return status;
    }

    public String walletDebitId() {
        return walletDebitId;
    }

    public String walletRefundId() {
        return walletRefundId;
    }

    public String providerPaymentId() {
        return providerPaymentId;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Long version() {
        return version;
    }
}
