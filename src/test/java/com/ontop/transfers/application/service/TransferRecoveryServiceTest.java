package com.ontop.transfers.application.service;

import com.ontop.transfers.application.exception.ConcurrentTransferUpdateException;
import com.ontop.transfers.application.port.in.RecoverStuckTransfersUseCase.RecoveryReport;
import com.ontop.transfers.application.port.out.PaymentStatusPort;
import com.ontop.transfers.application.port.out.PaymentStatusPort.PaymentLookup;
import com.ontop.transfers.application.port.out.ProviderPaymentStatus;
import com.ontop.transfers.application.port.out.TransferRepository;
import com.ontop.transfers.application.port.out.WalletOperationResult;
import com.ontop.transfers.application.port.out.WalletTransactionPort;
import com.ontop.transfers.domain.model.ReviewReason;
import com.ontop.transfers.domain.model.Transfer;
import com.ontop.transfers.domain.model.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;

import static com.ontop.transfers.TestData.NOW;
import static com.ontop.transfers.TestData.USER_ID;
import static com.ontop.transfers.TestData.newTransfer;
import static com.ontop.transfers.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferRecoveryServiceTest {

    @Mock
    private TransferRepository transfers;
    @Mock
    private PaymentStatusPort paymentStatus;
    @Mock
    private WalletTransactionPort walletTransactions;

    private TransferRecoveryService recovery;

    @BeforeEach
    void setUp() {
        recovery = new TransferRecoveryService(transfers, paymentStatus,
                new PaymentOutcomeHandler(transfers, walletTransactions), Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(15), 100);
        lenient().when(transfers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void givenStuck(Transfer... stuck) {
        when(transfers.findStuck(eq(TransferStatus.recoverable()), eq(NOW.minus(Duration.ofMinutes(15))), anyInt()))
                .thenReturn(List.of(stuck));
    }

    private static Transfer debited() {
        Transfer transfer = newTransfer("20.00");
        transfer.markWalletDebited("w-1");
        return transfer;
    }

    private static Transfer inReview(ReviewReason reason) {
        Transfer transfer = debited();
        transfer.markOutcomeUnknown(reason, "timeout");
        return transfer;
    }

    @Test
    void createdTransferGoesToManualReviewBecauseTheDebitIsUnknown() {
        Transfer created = newTransfer("20.00");
        givenStuck(created);

        RecoveryReport report = recovery.recover();

        assertThat(created.status()).isEqualTo(TransferStatus.IN_REVIEW);
        assertThat(created.reviewReason()).isEqualTo(ReviewReason.INTERRUPTED);
        assertThat(report).isEqualTo(new RecoveryReport(1, 1, 0, 0));
        verifyNoInteractions(paymentStatus, walletTransactions);
    }

    @Test
    void paymentFoundAtProviderCompletesTheTransfer() {
        Transfer transfer = inReview(ReviewReason.PAYMENT_UNKNOWN);
        givenStuck(transfer);
        when(paymentStatus.lookup(transfer.id()))
                .thenReturn(new PaymentLookup.Found("pay-1", ProviderPaymentStatus.COMPLETED, null));

        recovery.recover();

        assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.providerPaymentId()).isEqualTo("pay-1");
    }

    @Test
    void paymentNotFoundAfterGracePeriodRefundsTheWallet() {
        Transfer transfer = debited();
        givenStuck(transfer);
        when(paymentStatus.lookup(transfer.id())).thenReturn(new PaymentLookup.NotFound());
        when(walletTransactions.topUp(USER_ID, usd("20.00"))).thenReturn(new WalletOperationResult.Succeeded("w-2"));

        recovery.recover();

        assertThat(transfer.status()).isEqualTo(TransferStatus.REFUNDED);
    }

    @Test
    void processingTransferIsCompletedWhenTheCallbackWasMissed() {
        Transfer transfer = debited();
        transfer.markPaymentSubmitted("pay-1");
        givenStuck(transfer);
        when(paymentStatus.lookup(transfer.id()))
                .thenReturn(new PaymentLookup.Found("pay-1", ProviderPaymentStatus.COMPLETED, null));

        recovery.recover();

        assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void processingTransferUnknownToProviderIsAConflictNotARefund() {
        Transfer transfer = debited();
        transfer.markPaymentSubmitted("pay-1");
        givenStuck(transfer);
        when(paymentStatus.lookup(transfer.id())).thenReturn(new PaymentLookup.NotFound());

        RecoveryReport report = recovery.recover();

        assertThat(report.conflicts()).isEqualTo(1);
        assertThat(transfer.status()).isEqualTo(TransferStatus.PROCESSING);
        verifyNoInteractions(walletTransactions);
    }

    @Test
    void providerUnavailableLeavesTheTransferForTheNextRun() {
        Transfer transfer = inReview(ReviewReason.PAYMENT_UNKNOWN);
        givenStuck(transfer);
        when(paymentStatus.lookup(transfer.id())).thenReturn(new PaymentLookup.Unavailable("lookup disabled"));

        RecoveryReport report = recovery.recover();

        assertThat(report).isEqualTo(new RecoveryReport(1, 0, 1, 0));
        verify(transfers, never()).save(any());
    }

    @Test
    void reviewForAnUnknownWalletDebitIsNeverResolvedAutomatically() {
        Transfer transfer = newTransfer("20.00");
        transfer.markOutcomeUnknown(ReviewReason.WALLET_DEBIT_UNKNOWN, "HTTP 500");
        givenStuck(transfer);

        RecoveryReport report = recovery.recover();

        assertThat(report.waiting()).isEqualTo(1);
        verifyNoInteractions(paymentStatus, walletTransactions);
    }

    @Test
    void paymentFailedTransferRetriesTheRefund() {
        Transfer transfer = debited();
        transfer.markPaymentFailed("bank rejected");
        givenStuck(transfer);
        when(walletTransactions.topUp(USER_ID, usd("20.00"))).thenReturn(new WalletOperationResult.Succeeded("w-2"));

        recovery.recover();

        assertThat(transfer.status()).isEqualTo(TransferStatus.REFUNDED);
    }

    @Test
    void concurrentUpdateIsCountedAndSkipped() {
        Transfer transfer = newTransfer("20.00");
        givenStuck(transfer);
        when(transfers.save(any())).thenThrow(new ConcurrentTransferUpdateException(transfer.id()));

        RecoveryReport report = recovery.recover();

        assertThat(report.conflicts()).isEqualTo(1);
        verify(walletTransactions, never()).topUp(anyLong(), any());
    }
}
