package com.ontop.transfers.application.service;

import com.ontop.transfers.application.exception.PaymentOutcomeConflictException;
import com.ontop.transfers.application.port.out.ProviderPaymentStatus;
import com.ontop.transfers.application.port.out.TransferRepository;
import com.ontop.transfers.application.port.out.WalletOperationResult;
import com.ontop.transfers.application.port.out.WalletTransactionPort;
import com.ontop.transfers.domain.exception.IllegalTransferTransitionException;
import com.ontop.transfers.domain.model.ReviewReason;
import com.ontop.transfers.domain.model.Transfer;
import com.ontop.transfers.domain.model.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The second half of the saga, shared by the execute endpoint, the provider callback and the recovery job, so the
 * three paths can never treat the same provider answer differently.
 */
public final class PaymentOutcomeHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentOutcomeHandler.class);

    private final TransferRepository transfers;
    private final WalletTransactionPort walletTransactions;

    public PaymentOutcomeHandler(TransferRepository transfers, WalletTransactionPort walletTransactions) {
        this.transfers = transfers;
        this.walletTransactions = walletTransactions;
    }

    /**
     * Moves the transfer to match the provider's status. Repeating the same status is a no-op; a status that
     * contradicts the transfer (e.g. COMPLETED after a refund) raises {@link PaymentOutcomeConflictException}.
     */
    public Transfer apply(Transfer transfer, String paymentId, ProviderPaymentStatus status, String detail) {
        if (transfer.status() == TransferStatus.IN_REVIEW && transfer.reviewReason() != ReviewReason.PAYMENT_UNKNOWN) {
            throw new PaymentOutcomeConflictException(transfer.id(),
                    "in review for " + transfer.reviewReason() + ", needs manual resolution");
        }
        try {
            return switch (status) {
                case PROCESSING -> submitted(transfer, paymentId);
                case COMPLETED -> completed(transfer, paymentId);
                case FAILED -> failed(transfer, detail);
            };
        } catch (IllegalTransferTransitionException e) {
            throw new PaymentOutcomeConflictException(transfer.id(), "provider says " + status + ", transfer is "
                    + transfer.status());
        }
    }

    /**
     * Marks the payment as failed and gives the full amount (fee included) back to the wallet.
     */
    public Transfer failAndRefund(Transfer transfer, String reason) {
        transfer.markPaymentFailed(reason);
        return refund(transfers.save(transfer));
    }

    /**
     * Refunds a PAYMENT_FAILED transfer. A rejected refund leaves it PAYMENT_FAILED so the recovery job retries; an
     * ambiguous one goes to review, because retrying could refund twice.
     */
    public Transfer refund(Transfer transfer) {
        WalletOperationResult result = walletTransactions.topUp(transfer.userId(), transfer.amount());
        return switch (result) {
            case WalletOperationResult.Succeeded succeeded -> {
                transfer.markRefunded(succeeded.walletTransactionId());
                yield transfers.save(transfer);
            }
            case WalletOperationResult.Rejected rejected -> {
                log.error("Transfer {}: refund rejected ({}), will be retried", transfer.id(), rejected.reason());
                yield transfer;
            }
            case WalletOperationResult.Unknown unknown -> {
                log.error("Transfer {}: refund outcome unknown ({}), sent to review", transfer.id(), unknown.reason());
                transfer.markOutcomeUnknown(ReviewReason.REFUND_UNKNOWN, "Refund outcome unknown: " + unknown.reason());
                yield transfers.save(transfer);
            }
        };
    }

    private Transfer submitted(Transfer transfer, String paymentId) {
        if (transfer.status() == TransferStatus.PROCESSING || transfer.status() == TransferStatus.COMPLETED) {
            return transfer;
        }
        transfer.markPaymentSubmitted(paymentId);
        return transfers.save(transfer);
    }

    private Transfer completed(Transfer transfer, String paymentId) {
        if (transfer.status() == TransferStatus.COMPLETED) {
            return transfer;
        }
        if (transfer.status() != TransferStatus.PROCESSING) {
            transfer.markPaymentSubmitted(paymentId);
        }
        transfer.markCompleted();
        return transfers.save(transfer);
    }

    private Transfer failed(Transfer transfer, String detail) {
        return switch (transfer.status()) {
            case REFUNDED -> transfer;
            case PAYMENT_FAILED -> refund(transfer);
            default -> failAndRefund(transfer, detail == null ? "Payment failed at provider" : detail);
        };
    }
}
