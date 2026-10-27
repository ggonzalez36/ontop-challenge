package com.ontop.transfers.application.service;

import com.ontop.transfers.application.exception.ConcurrentTransferUpdateException;
import com.ontop.transfers.application.exception.PaymentOutcomeConflictException;
import com.ontop.transfers.application.port.in.RecoverStuckTransfersUseCase;
import com.ontop.transfers.application.port.out.PaymentStatusPort;
import com.ontop.transfers.application.port.out.PaymentStatusPort.PaymentLookup;
import com.ontop.transfers.application.port.out.TransferRepository;
import com.ontop.transfers.domain.model.ReviewReason;
import com.ontop.transfers.domain.model.Transfer;
import com.ontop.transfers.domain.model.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Drives transfers that stopped moving (crash, timeout, missed callback) towards a final state. Never guesses: when
 * the wallet side is unknown it leaves the transfer for manual review.
 *
 * <table>
 *   <tr><th>Status</th><th>Action</th></tr>
 *   <tr><td>CREATED</td><td>wallet debit unconfirmed → IN_REVIEW (INTERRUPTED)</td></tr>
 *   <tr><td>WALLET_DEBITED, PROCESSING, IN_REVIEW(PAYMENT_UNKNOWN)</td><td>ask the provider by reference</td></tr>
 *   <tr><td>PAYMENT_FAILED</td><td>retry the refund</td></tr>
 *   <tr><td>other IN_REVIEW</td><td>wait for manual resolution</td></tr>
 * </table>
 */
public final class TransferRecoveryService implements RecoverStuckTransfersUseCase {

    private static final Logger log = LoggerFactory.getLogger(TransferRecoveryService.class);

    private final TransferRepository transfers;
    private final PaymentStatusPort paymentStatus;
    private final PaymentOutcomeHandler outcomes;
    private final Clock clock;
    private final Duration stuckAfter;
    private final int batchSize;

    public TransferRecoveryService(TransferRepository transfers, PaymentStatusPort paymentStatus,
                                   PaymentOutcomeHandler outcomes, Clock clock, Duration stuckAfter, int batchSize) {
        this.transfers = transfers;
        this.paymentStatus = paymentStatus;
        this.outcomes = outcomes;
        this.clock = clock;
        this.stuckAfter = stuckAfter;
        this.batchSize = batchSize;
    }

    @Override
    public RecoveryReport recover() {
        Instant cutoff = clock.instant().minus(stuckAfter);
        List<Transfer> stuck = transfers.findStuck(TransferStatus.recoverable(), cutoff, batchSize);
        int advanced = 0;
        int waiting = 0;
        int conflicts = 0;
        for (Transfer transfer : stuck) {
            TransferStatus before = transfer.status();
            try {
                Transfer after = recoverOne(transfer);
                if (after.status() != before) {
                    advanced++;
                    log.info("Recovery moved transfer {} from {} to {}", after.id(), before, after.status());
                } else {
                    waiting++;
                }
            } catch (ConcurrentTransferUpdateException | PaymentOutcomeConflictException e) {
                conflicts++;
                log.warn("Recovery skipped transfer {}: {}", transfer.id(), e.getMessage());
            }
        }
        return new RecoveryReport(stuck.size(), advanced, waiting, conflicts);
    }

    private Transfer recoverOne(Transfer transfer) {
        return switch (transfer.status()) {
            case CREATED -> {
                transfer.markOutcomeUnknown(ReviewReason.INTERRUPTED,
                        "Interrupted before the wallet confirmed the debit");
                yield transfers.save(transfer);
            }
            case WALLET_DEBITED, PROCESSING -> askProvider(transfer);
            case IN_REVIEW -> transfer.reviewReason() == ReviewReason.PAYMENT_UNKNOWN ? askProvider(transfer) : transfer;
            case PAYMENT_FAILED -> outcomes.refund(transfer);
            case COMPLETED, REFUNDED, REJECTED -> transfer;
        };
    }

    private Transfer askProvider(Transfer transfer) {
        PaymentLookup lookup = paymentStatus.lookup(transfer.id());
        return switch (lookup) {
            case PaymentLookup.Found found -> outcomes.apply(transfer, found.paymentId(), found.status(), found.detail());
            case PaymentLookup.NotFound notFound -> paymentNotFound(transfer);
            case PaymentLookup.Unavailable unavailable -> transfer;
        };
    }

    /**
     * The provider has no payment with our reference after the grace period, so the money never left Ontop's account
     * and the debit can be refunded. A PROCESSING transfer, however, has a payment id: the provider losing it is a
     * contradiction for a human to look at.
     */
    private Transfer paymentNotFound(Transfer transfer) {
        if (transfer.status() == TransferStatus.PROCESSING) {
            throw new PaymentOutcomeConflictException(transfer.id(), "provider does not know payment "
                    + transfer.providerPaymentId());
        }
        return outcomes.failAndRefund(transfer, "Payment not found at provider");
    }
}
