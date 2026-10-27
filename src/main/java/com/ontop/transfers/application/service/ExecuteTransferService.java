package com.ontop.transfers.application.service;

import com.ontop.transfers.application.exception.IdempotencyKeyConflictException;
import com.ontop.transfers.application.exception.RecipientNotFoundException;
import com.ontop.transfers.application.exception.WalletOperationRejectedException;
import com.ontop.transfers.application.port.in.ExecuteTransferCommand;
import com.ontop.transfers.application.port.in.ExecuteTransferUseCase;
import com.ontop.transfers.application.port.in.TransferOutcome;
import com.ontop.transfers.application.port.out.PaymentOrder;
import com.ontop.transfers.application.port.out.PaymentProviderPort;
import com.ontop.transfers.application.port.out.PaymentResult;
import com.ontop.transfers.application.port.out.RecipientRepository;
import com.ontop.transfers.application.port.out.TransferRepository;
import com.ontop.transfers.application.port.out.WalletBalancePort;
import com.ontop.transfers.application.port.out.WalletOperationResult;
import com.ontop.transfers.application.port.out.WalletTransactionPort;
import com.ontop.transfers.domain.fee.FeePolicy;
import com.ontop.transfers.domain.model.IdempotencyKey;
import com.ontop.transfers.domain.model.Money;
import com.ontop.transfers.domain.model.Recipient;
import com.ontop.transfers.domain.model.ReviewReason;
import com.ontop.transfers.domain.model.Transfer;
import com.ontop.transfers.domain.model.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Optional;

/**
 * Orchestrates the withdrawal saga: debit the wallet, pay through the provider, refund the wallet if the payment
 * fails. The transfer is persisted before and after every remote call, so a crash always leaves a known last step
 * for the recovery job. See ADR 002.
 */
public final class ExecuteTransferService implements ExecuteTransferUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExecuteTransferService.class);

    private final RecipientRepository recipients;
    private final TransferRepository transfers;
    private final WalletBalancePort walletBalance;
    private final WalletTransactionPort walletTransactions;
    private final PaymentProviderPort payments;
    private final PaymentOutcomeHandler outcomes;
    private final FeePolicy feePolicy;
    private final Clock clock;

    public ExecuteTransferService(RecipientRepository recipients, TransferRepository transfers,
                                  WalletBalancePort walletBalance, WalletTransactionPort walletTransactions,
                                  PaymentProviderPort payments, PaymentOutcomeHandler outcomes, FeePolicy feePolicy,
                                  Clock clock) {
        this.recipients = recipients;
        this.transfers = transfers;
        this.walletBalance = walletBalance;
        this.walletTransactions = walletTransactions;
        this.payments = payments;
        this.outcomes = outcomes;
        this.feePolicy = feePolicy;
        this.clock = clock;
    }

    @Override
    public TransferOutcome execute(ExecuteTransferCommand command) {
        Optional<Transfer> existing = transfers.findByUserIdAndIdempotencyKey(command.userId(), command.idempotencyKey());
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }

        Recipient recipient = recipients.findByIdAndUserId(command.recipientId(), command.userId())
                .orElseThrow(() -> new RecipientNotFoundException(command.recipientId()));
        Money balance = walletBalance.getBalance(command.userId());

        Transfer transfer = Transfer.create(command.userId(), recipient, command.amount(), balance, feePolicy,
                new IdempotencyKey(command.idempotencyKey(), command.fingerprint()), clock.instant());
        transfer = transfers.saveNew(transfer);
        log.info("Transfer {} created for user {}: amount={} fee={}", transfer.id(), transfer.userId(),
                transfer.amount(), transfer.fee());

        transfer = debitWallet(transfer);
        if (transfer.status() == TransferStatus.WALLET_DEBITED) {
            transfer = submitPayment(transfer);
        }
        return TransferOutcome.executed(transfer);
    }

    private TransferOutcome replay(Transfer existing, ExecuteTransferCommand command) {
        if (!existing.idempotencyKey().matches(command.fingerprint())) {
            throw new IdempotencyKeyConflictException(command.idempotencyKey());
        }
        log.info("Replaying transfer {} for Idempotency-Key {}", existing.id(), command.idempotencyKey());
        return TransferOutcome.replayed(existing);
    }

    private Transfer debitWallet(Transfer transfer) {
        WalletOperationResult result = walletTransactions.withdraw(transfer.userId(), transfer.amount());
        switch (result) {
            case WalletOperationResult.Succeeded succeeded -> transfer.markWalletDebited(succeeded.walletTransactionId());
            case WalletOperationResult.Rejected rejected -> {
                transfer.reject(rejected.reason());
                transfers.save(transfer);
                throw new WalletOperationRejectedException(rejected.reason());
            }
            case WalletOperationResult.Unknown unknown -> {
                log.warn("Transfer {}: wallet debit outcome unknown ({}), sent to review", transfer.id(), unknown.reason());
                transfer.markOutcomeUnknown(ReviewReason.WALLET_DEBIT_UNKNOWN,
                        "Wallet debit outcome unknown: " + unknown.reason());
            }
        }
        return transfers.save(transfer);
    }

    private Transfer submitPayment(Transfer transfer) {
        PaymentResult result = payments.pay(new PaymentOrder(transfer.id(), transfer.recipient(), transfer.netAmount()));
        return switch (result) {
            case PaymentResult.Accepted accepted -> {
                transfer.markPaymentSubmitted(accepted.paymentId());
                yield transfers.save(transfer);
            }
            case PaymentResult.Rejected rejected -> outcomes.failAndRefund(transfer, rejected.reason());
            case PaymentResult.Unknown unknown -> {
                log.warn("Transfer {}: payment outcome unknown ({}), sent to review", transfer.id(), unknown.reason());
                transfer.markOutcomeUnknown(ReviewReason.PAYMENT_UNKNOWN, "Payment outcome unknown: " + unknown.reason());
                yield transfers.save(transfer);
            }
        };
    }
}
