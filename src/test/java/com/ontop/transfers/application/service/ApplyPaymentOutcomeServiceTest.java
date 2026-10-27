package com.ontop.transfers.application.service;

import com.ontop.transfers.application.exception.PaymentOutcomeConflictException;
import com.ontop.transfers.application.exception.TransferNotFoundException;
import com.ontop.transfers.application.port.in.ApplyPaymentOutcomeUseCase.PaymentNotification;
import com.ontop.transfers.application.port.out.ProviderPaymentStatus;
import com.ontop.transfers.application.port.out.TransferRepository;
import com.ontop.transfers.application.port.out.WalletOperationResult;
import com.ontop.transfers.application.port.out.WalletTransactionPort;
import com.ontop.transfers.domain.model.Transfer;
import com.ontop.transfers.domain.model.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static com.ontop.transfers.TestData.USER_ID;
import static com.ontop.transfers.TestData.newTransfer;
import static com.ontop.transfers.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplyPaymentOutcomeServiceTest {

    @Mock
    private TransferRepository transfers;
    @Mock
    private WalletTransactionPort walletTransactions;

    private ApplyPaymentOutcomeService service;
    private Transfer processing;

    @BeforeEach
    void setUp() {
        service = new ApplyPaymentOutcomeService(transfers, new PaymentOutcomeHandler(transfers, walletTransactions));
        processing = newTransfer("20.00");
        processing.markWalletDebited("w-1");
        processing.markPaymentSubmitted("pay-1");
        lenient().when(transfers.findById(processing.id())).thenReturn(Optional.of(processing));
        lenient().when(transfers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private PaymentNotification notification(String paymentId, ProviderPaymentStatus status) {
        return new PaymentNotification(processing.id(), paymentId, status, "bank rejected");
    }

    @Test
    void completedNotificationCompletesTheTransfer() {
        assertThat(service.apply(notification("pay-1", ProviderPaymentStatus.COMPLETED)).status())
                .isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void repeatedNotificationIsANoOp() {
        service.apply(notification("pay-1", ProviderPaymentStatus.COMPLETED));
        service.apply(notification("pay-1", ProviderPaymentStatus.COMPLETED));

        verify(transfers, times(1)).save(any());
    }

    @Test
    void failedNotificationRefundsTheWallet() {
        when(walletTransactions.topUp(USER_ID, usd("20.00"))).thenReturn(new WalletOperationResult.Succeeded("w-2"));

        Transfer transfer = service.apply(notification("pay-1", ProviderPaymentStatus.FAILED));

        assertThat(transfer.status()).isEqualTo(TransferStatus.REFUNDED);
        assertThat(transfer.failureReason()).isEqualTo("bank rejected");
    }

    @Test
    void failureAfterCompletionIsAConflict() {
        service.apply(notification("pay-1", ProviderPaymentStatus.COMPLETED));

        assertThatThrownBy(() -> service.apply(notification("pay-1", ProviderPaymentStatus.FAILED)))
                .isInstanceOf(PaymentOutcomeConflictException.class);
        verify(walletTransactions, never()).topUp(anyLong(), any());
    }

    @Test
    void mismatchedPaymentIdIsAConflict() {
        assertThatThrownBy(() -> service.apply(notification("another-payment", ProviderPaymentStatus.COMPLETED)))
                .isInstanceOf(PaymentOutcomeConflictException.class);
    }

    @Test
    void unknownTransferIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(transfers.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(new PaymentNotification(unknown, "pay-1",
                ProviderPaymentStatus.COMPLETED, null))).isInstanceOf(TransferNotFoundException.class);
    }
}
