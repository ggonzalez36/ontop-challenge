package com.ontop.transfers.domain.model;

import com.ontop.transfers.domain.event.TransferEvent;
import com.ontop.transfers.domain.exception.IllegalTransferTransitionException;
import com.ontop.transfers.domain.exception.InsufficientFundsException;
import com.ontop.transfers.domain.exception.InvalidAmountException;
import org.junit.jupiter.api.Test;

import static com.ontop.transfers.TestData.NOW;
import static com.ontop.transfers.TestData.TEN_PERCENT;
import static com.ontop.transfers.TestData.USER_ID;
import static com.ontop.transfers.TestData.newTransfer;
import static com.ontop.transfers.TestData.recipient;
import static com.ontop.transfers.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransferTest {

    private static final IdempotencyKey KEY = new IdempotencyKey("k1", "fp");

    @Test
    void createDeductsFeeFromAmountSent() {
        Transfer transfer = newTransfer("20.00");

        assertThat(transfer.status()).isEqualTo(TransferStatus.CREATED);
        assertThat(transfer.amount()).isEqualTo(usd("20.00"));
        assertThat(transfer.fee()).isEqualTo(usd("2.00"));
        assertThat(transfer.netAmount()).isEqualTo(usd("18.00"));
    }

    @Test
    void createCopiesRecipientSnapshot() {
        Transfer transfer = newTransfer("20.00");

        assertThat(transfer.recipient().fullName()).isEqualTo("Lucho Rodriguez");
        assertThat(transfer.recipient().accountLast4()).isEqualTo("6711");
    }

    @Test
    void createRejectsAmountAboveBalance() {
        assertThatThrownBy(() -> Transfer.create(USER_ID, recipient(), usd("100.01"), usd("100.00"), TEN_PERCENT, KEY, NOW))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("requested 100.01 USD, available 100.00 USD");
    }

    @Test
    void createAllowsWithdrawingTheWholeBalance() {
        Transfer transfer = Transfer.create(USER_ID, recipient(), usd("100.00"), usd("100.00"), TEN_PERCENT, KEY, NOW);

        assertThat(transfer.netAmount()).isEqualTo(usd("90.00"));
    }

    @Test
    void createRejectsNonPositiveAmount() {
        assertThatThrownBy(() -> Transfer.create(USER_ID, recipient(), usd("0"), usd("100"), TEN_PERCENT, KEY, NOW))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void createRejectsAmountThatDoesNotCoverTheFee() {
        // a policy that takes the whole amount leaves nothing for the recipient
        assertThatThrownBy(() -> Transfer.create(USER_ID, recipient(), usd("0.01"), usd("100"),
                amount -> amount, KEY, NOW))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("too small");
    }

    @Test
    void createRejectsCurrencyDifferentFromRecipientAccount() {
        assertThatThrownBy(() -> Transfer.create(USER_ID, recipient(), Money.of("10", "EUR"), Money.of("100", "EUR"),
                TEN_PERCENT, KEY, NOW))
                .isInstanceOf(InvalidAmountException.class)
                .hasMessageContaining("not supported by this recipient");
    }

    @Test
    void happyPathTransitions() {
        Transfer transfer = newTransfer("20.00");

        transfer.markWalletDebited("w-1");
        transfer.markPaymentSubmitted("p-1");
        transfer.markCompleted();

        assertThat(transfer.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(transfer.walletDebitId()).isEqualTo("w-1");
        assertThat(transfer.providerPaymentId()).isEqualTo("p-1");
    }

    @Test
    void compensationTransitions() {
        Transfer transfer = newTransfer("20.00");

        transfer.markWalletDebited("w-1");
        transfer.markPaymentFailed("bank rejected");
        transfer.markRefunded("w-2");

        assertThat(transfer.status()).isEqualTo(TransferStatus.REFUNDED);
        assertThat(transfer.failureReason()).isEqualTo("bank rejected");
        assertThat(transfer.walletRefundId()).isEqualTo("w-2");
    }

    @Test
    void cannotPayBeforeDebitingTheWallet() {
        Transfer transfer = newTransfer("20.00");

        assertThatThrownBy(() -> transfer.markPaymentSubmitted("p-1"))
                .isInstanceOf(IllegalTransferTransitionException.class)
                .hasMessageContaining("from CREATED to PROCESSING");
    }

    @Test
    void cannotRefundATransferInReview() {
        Transfer transfer = newTransfer("20.00");
        transfer.markWalletDebited("w-1");
        transfer.markOutcomeUnknown(ReviewReason.PAYMENT_UNKNOWN, "provider timeout");

        assertThatThrownBy(() -> transfer.markRefunded("w-2"))
                .isInstanceOf(IllegalTransferTransitionException.class);
    }

    @Test
    void recordsOneEventPerChangeAndHandsThemOutOnce() {
        Transfer transfer = newTransfer("20.00");
        transfer.markWalletDebited("w-1");
        transfer.markPaymentSubmitted("p-1");

        assertThat(transfer.pullEvents()).extracting(TransferEvent::type)
                .containsExactly("TransferCreated", "WalletDebited", "PaymentSubmitted");
        assertThat(transfer.pullEvents()).isEmpty();
    }

    @Test
    void reviewReasonIsSetInReviewAndClearedWhenResolved() {
        Transfer transfer = newTransfer("20.00");
        transfer.markWalletDebited("w-1");
        transfer.markOutcomeUnknown(ReviewReason.PAYMENT_UNKNOWN, "timeout");
        assertThat(transfer.reviewReason()).isEqualTo(ReviewReason.PAYMENT_UNKNOWN);

        transfer.markPaymentSubmitted("p-1");

        assertThat(transfer.status()).isEqualTo(TransferStatus.PROCESSING);
        assertThat(transfer.reviewReason()).isNull();
    }

    @Test
    void ambiguousRefundGoesToReview() {
        Transfer transfer = newTransfer("20.00");
        transfer.markWalletDebited("w-1");
        transfer.markPaymentFailed("bank rejected");

        transfer.markOutcomeUnknown(ReviewReason.REFUND_UNKNOWN, "timeout");

        assertThat(transfer.status()).isEqualTo(TransferStatus.IN_REVIEW);
    }

    @Test
    void inFlightOnlyBetweenCreationAndDebitConfirmation() {
        assertThat(TransferStatus.CREATED.isInFlight()).isTrue();
        assertThat(TransferStatus.WALLET_DEBITED.isInFlight()).isTrue();
        assertThat(TransferStatus.PROCESSING.isInFlight()).isFalse();
        assertThat(TransferStatus.REFUNDED.isTerminal()).isTrue();
    }
}
