package com.ontop.transfers.domain.event;

import com.ontop.transfers.domain.model.Money;
import com.ontop.transfers.domain.model.ReviewReason;

import java.util.UUID;

/**
 * Facts recorded by the {@link com.ontop.transfers.domain.model.Transfer} aggregate on each state change. They are
 * stored with the transfer in the same transaction and feed the outbox, the ledger and the history read model.
 */
public sealed interface TransferEvent {

    UUID transferId();

    long userId();

    default String type() {
        return getClass().getSimpleName();
    }

    record TransferCreated(UUID transferId, long userId, Money amount, Money fee, Money netAmount)
            implements TransferEvent {
    }

    record WalletDebited(UUID transferId, long userId, Money amount, String walletTransactionId)
            implements TransferEvent {
    }

    record PaymentSubmitted(UUID transferId, long userId, Money netAmount, String paymentId)
            implements TransferEvent {
    }

    record TransferCompleted(UUID transferId, long userId, Money amount, Money fee, Money netAmount)
            implements TransferEvent {
    }

    record PaymentFailed(UUID transferId, long userId, String reason) implements TransferEvent {
    }

    record TransferRefunded(UUID transferId, long userId, Money amount, String walletTransactionId)
            implements TransferEvent {
    }

    record TransferSentToReview(UUID transferId, long userId, ReviewReason reason, String detail)
            implements TransferEvent {
    }

    record TransferRejected(UUID transferId, long userId, String reason) implements TransferEvent {
    }
}
