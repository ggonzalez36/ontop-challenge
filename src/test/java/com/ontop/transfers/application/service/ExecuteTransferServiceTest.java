package com.ontop.transfers.application.service;

import com.ontop.transfers.application.exception.IdempotencyKeyConflictException;
import com.ontop.transfers.application.exception.RecipientNotFoundException;
import com.ontop.transfers.application.exception.WalletOperationRejectedException;
import com.ontop.transfers.application.exception.WalletUnavailableException;
import com.ontop.transfers.application.port.in.ExecuteTransferCommand;
import com.ontop.transfers.application.port.in.TransferOutcome;
import com.ontop.transfers.application.port.out.PaymentOrder;
import com.ontop.transfers.application.port.out.PaymentProviderPort;
import com.ontop.transfers.application.port.out.PaymentResult;
import com.ontop.transfers.application.port.out.RecipientRepository;
import com.ontop.transfers.application.port.out.TransferRepository;
import com.ontop.transfers.application.port.out.WalletBalancePort;
import com.ontop.transfers.application.port.out.WalletOperationResult;
import com.ontop.transfers.application.port.out.WalletTransactionPort;
import com.ontop.transfers.domain.exception.InsufficientFundsException;
import com.ontop.transfers.domain.model.IdempotencyKey;
import com.ontop.transfers.domain.model.ReviewReason;
import com.ontop.transfers.domain.model.Transfer;
import com.ontop.transfers.domain.model.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.ontop.transfers.TestData.NOW;
import static com.ontop.transfers.TestData.SEEDED_RECIPIENT_ID;
import static com.ontop.transfers.TestData.TEN_PERCENT;
import static com.ontop.transfers.TestData.USER_ID;
import static com.ontop.transfers.TestData.recipient;
import static com.ontop.transfers.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecuteTransferServiceTest {

    @Mock
    private RecipientRepository recipients;
    @Mock
    private TransferRepository transfers;
    @Mock
    private WalletBalancePort walletBalance;
    @Mock
    private WalletTransactionPort walletTransactions;
    @Mock
    private PaymentProviderPort payments;

    private ExecuteTransferService service;

    private final ExecuteTransferCommand command =
            new ExecuteTransferCommand(USER_ID, SEEDED_RECIPIENT_ID, usd("20.00"), "key-1");

    @BeforeEach
    void setUp() {
        service = new ExecuteTransferService(recipients, transfers, walletBalance, walletTransactions, payments,
                new PaymentOutcomeHandler(transfers, walletTransactions), TEN_PERCENT, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void givenValidRequest() {
        when(transfers.findByUserIdAndIdempotencyKey(USER_ID, "key-1")).thenReturn(Optional.empty());
        when(recipients.findByIdAndUserId(SEEDED_RECIPIENT_ID, USER_ID)).thenReturn(Optional.of(recipient()));
        when(walletBalance.getBalance(USER_ID)).thenReturn(usd("2500.00"));
        when(transfers.saveNew(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transfers.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void givenWalletDebitedAndProviderAnswers(PaymentResult result) {
        givenValidRequest();
        when(walletTransactions.withdraw(USER_ID, usd("20.00"))).thenReturn(new WalletOperationResult.Succeeded("59974"));
        when(payments.pay(any())).thenReturn(result);
    }

    @Test
    void happyPathDebitsWalletAndSubmitsNetAmountToProvider() {
        givenWalletDebitedAndProviderAnswers(new PaymentResult.Accepted("pay-1", "Processing"));

        TransferOutcome outcome = service.execute(command);

        Transfer transfer = outcome.transfer();
        assertThat(outcome.replayed()).isFalse();
        assertThat(transfer.status()).isEqualTo(TransferStatus.PROCESSING);
        assertThat(transfer.walletDebitId()).isEqualTo("59974");
        assertThat(transfer.providerPaymentId()).isEqualTo("pay-1");

        ArgumentCaptor<PaymentOrder> order = ArgumentCaptor.forClass(PaymentOrder.class);
        verify(payments).pay(order.capture());
        assertThat(order.getValue().amount()).isEqualTo(usd("18.00"));
        assertThat(order.getValue().transferId()).isEqualTo(transfer.id());
        verify(walletTransactions, never()).topUp(anyLong(), any());
    }

    @Test
    void providerRejectionRefundsTheFullAmountIncludingFee() {
        givenWalletDebitedAndProviderAnswers(new PaymentResult.Rejected("bank rejected payment"));
        when(walletTransactions.topUp(USER_ID, usd("20.00"))).thenReturn(new WalletOperationResult.Succeeded("59975"));

        Transfer transfer = service.execute(command).transfer();

        assertThat(transfer.status()).isEqualTo(TransferStatus.REFUNDED);
        assertThat(transfer.walletRefundId()).isEqualTo("59975");
        assertThat(transfer.failureReason()).isEqualTo("bank rejected payment");
    }

    @Test
    void rejectedRefundLeavesTransferInPaymentFailedForRetry() {
        givenWalletDebitedAndProviderAnswers(new PaymentResult.Rejected("bank rejected payment"));
        when(walletTransactions.topUp(USER_ID, usd("20.00"))).thenReturn(new WalletOperationResult.Rejected("HTTP 400"));

        Transfer transfer = service.execute(command).transfer();

        assertThat(transfer.status()).isEqualTo(TransferStatus.PAYMENT_FAILED);
        assertThat(transfer.walletRefundId()).isNull();
    }

    @Test
    void ambiguousRefundGoesToReviewInsteadOfRetrying() {
        givenWalletDebitedAndProviderAnswers(new PaymentResult.Rejected("bank rejected payment"));
        when(walletTransactions.topUp(USER_ID, usd("20.00"))).thenReturn(new WalletOperationResult.Unknown("timeout"));

        Transfer transfer = service.execute(command).transfer();

        assertThat(transfer.status()).isEqualTo(TransferStatus.IN_REVIEW);
        assertThat(transfer.reviewReason()).isEqualTo(ReviewReason.REFUND_UNKNOWN);
    }

    @Test
    void providerTimeoutGoesToReviewAndIsNeverRefunded() {
        givenWalletDebitedAndProviderAnswers(new PaymentResult.Unknown("Read timed out"));

        Transfer transfer = service.execute(command).transfer();

        assertThat(transfer.status()).isEqualTo(TransferStatus.IN_REVIEW);
        assertThat(transfer.reviewReason()).isEqualTo(ReviewReason.PAYMENT_UNKNOWN);
        verify(walletTransactions, never()).topUp(anyLong(), any());
    }

    @Test
    void walletDebitWithUnknownOutcomeGoesToReviewWithoutCallingProvider() {
        givenValidRequest();
        when(walletTransactions.withdraw(USER_ID, usd("20.00"))).thenReturn(new WalletOperationResult.Unknown("HTTP 500"));

        Transfer transfer = service.execute(command).transfer();

        assertThat(transfer.status()).isEqualTo(TransferStatus.IN_REVIEW);
        assertThat(transfer.reviewReason()).isEqualTo(ReviewReason.WALLET_DEBIT_UNKNOWN);
        verifyNoInteractions(payments);
    }

    @Test
    void walletRejectionRejectsTransferAndThrows() {
        givenValidRequest();
        when(walletTransactions.withdraw(USER_ID, usd("20.00"))).thenReturn(new WalletOperationResult.Rejected("HTTP 400"));

        assertThatThrownBy(() -> service.execute(command)).isInstanceOf(WalletOperationRejectedException.class);

        ArgumentCaptor<Transfer> saved = ArgumentCaptor.forClass(Transfer.class);
        verify(transfers).save(saved.capture());
        assertThat(saved.getValue().status()).isEqualTo(TransferStatus.REJECTED);
        verifyNoInteractions(payments);
    }

    @Test
    void insufficientFundsMovesNoMoneyAndPersistsNothing() {
        when(transfers.findByUserIdAndIdempotencyKey(USER_ID, "key-1")).thenReturn(Optional.empty());
        when(recipients.findByIdAndUserId(SEEDED_RECIPIENT_ID, USER_ID)).thenReturn(Optional.of(recipient()));
        when(walletBalance.getBalance(USER_ID)).thenReturn(usd("19.99"));

        assertThatThrownBy(() -> service.execute(command)).isInstanceOf(InsufficientFundsException.class);

        verify(transfers, never()).saveNew(any());
        verifyNoInteractions(walletTransactions, payments);
    }

    @Test
    void recipientOfAnotherUserIsNotFound() {
        when(transfers.findByUserIdAndIdempotencyKey(USER_ID, "key-1")).thenReturn(Optional.empty());
        when(recipients.findByIdAndUserId(SEEDED_RECIPIENT_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(command)).isInstanceOf(RecipientNotFoundException.class);

        verifyNoInteractions(walletBalance, walletTransactions, payments);
    }

    @Test
    void walletUnavailablePropagatesBeforeAnythingIsPersisted() {
        when(transfers.findByUserIdAndIdempotencyKey(USER_ID, "key-1")).thenReturn(Optional.empty());
        when(recipients.findByIdAndUserId(SEEDED_RECIPIENT_ID, USER_ID)).thenReturn(Optional.of(recipient()));
        when(walletBalance.getBalance(USER_ID)).thenThrow(new WalletUnavailableException("HTTP 500"));

        assertThatThrownBy(() -> service.execute(command)).isInstanceOf(WalletUnavailableException.class);

        verify(transfers, never()).saveNew(any());
    }

    @Test
    void sameKeyAndSameRequestReplaysWithoutSideEffects() {
        Transfer existing = Transfer.create(USER_ID, recipient(), usd("20.00"), usd("2500.00"), TEN_PERCENT,
                new IdempotencyKey("key-1", command.fingerprint()), NOW);
        when(transfers.findByUserIdAndIdempotencyKey(USER_ID, "key-1")).thenReturn(Optional.of(existing));

        TransferOutcome outcome = service.execute(command);

        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.transfer()).isSameAs(existing);
        verifyNoInteractions(recipients, walletBalance, walletTransactions, payments);
    }

    @Test
    void sameKeyWithDifferentRequestIsAConflict() {
        Transfer existing = Transfer.create(USER_ID, recipient(), usd("50.00"), usd("2500.00"), TEN_PERCENT,
                new IdempotencyKey("key-1", "another-fingerprint"), NOW);
        when(transfers.findByUserIdAndIdempotencyKey(USER_ID, "key-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.execute(command)).isInstanceOf(IdempotencyKeyConflictException.class);

        verifyNoInteractions(walletTransactions, payments);
    }
}
