package com.ontop.transfers.application.service;

import com.ontop.transfers.application.exception.InvalidCursorException;
import com.ontop.transfers.application.exception.RecipientNotFoundException;
import com.ontop.transfers.application.port.in.GetTransactionHistoryUseCase.HistoryPage;
import com.ontop.transfers.application.port.in.ManageRecipientsUseCase.CreateRecipientCommand;
import com.ontop.transfers.application.port.in.QuoteTransferUseCase.TransferQuote;
import com.ontop.transfers.application.port.in.ReconcileTransfersUseCase.ReconciliationReport;
import com.ontop.transfers.application.port.out.RecipientRepository;
import com.ontop.transfers.application.port.out.ReconciliationRepository;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository.HistoryCursor;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository.HistoryEntry;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository.MovementType;
import com.ontop.transfers.application.port.out.WalletBalancePort;
import com.ontop.transfers.domain.exception.InvalidRecipientException;
import com.ontop.transfers.domain.model.Recipient;
import com.ontop.transfers.domain.model.TransferStatus;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.ontop.transfers.TestData.NOW;
import static com.ontop.transfers.TestData.TEN_PERCENT;
import static com.ontop.transfers.TestData.USER_ID;
import static com.ontop.transfers.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The read-side and CRUD use cases: small, so grouped in one class.
 */
class QueryServicesTest {

    @Nested
    class Quote {

        private final WalletBalancePort wallet = mock(WalletBalancePort.class);
        private final QuoteTransferService service = new QuoteTransferService(wallet, TEN_PERCENT);

        @Test
        void quotesFeeNetAmountAndBalance() {
            when(wallet.getBalance(USER_ID)).thenReturn(usd("99924.00"));

            TransferQuote quote = service.quote(USER_ID, usd("20"));

            assertThat(quote.breakdown().fee()).isEqualTo(usd("2.00"));
            assertThat(quote.breakdown().netAmount()).isEqualTo(usd("18.00"));
            assertThat(quote.availableBalance()).isEqualTo(usd("99924.00"));
            assertThat(quote.affordable()).isTrue();
        }

        @Test
        void flagsAmountsAboveTheBalance() {
            when(wallet.getBalance(USER_ID)).thenReturn(usd("10.00"));

            assertThat(service.quote(USER_ID, usd("20")).affordable()).isFalse();
        }
    }

    @Nested
    class Recipients {

        private final RecipientRepository repository = mock(RecipientRepository.class);
        private final RecipientService service = new RecipientService(repository);

        @Test
        void createsValidatedRecipient() {
            when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

            Recipient recipient = service.create(new CreateRecipientCommand(USER_ID, "Lucho", "Rodriguez", "3327809",
                    "623852453", "2300299907", "Chase", "USD"));

            assertThat(recipient.userId()).isEqualTo(USER_ID);
            assertThat(recipient.bankAccount().bankName()).isEqualTo("Chase");
        }

        @Test
        void rejectsUnknownCurrency() {
            assertThatThrownBy(() -> service.create(new CreateRecipientCommand(USER_ID, "Lucho", "Rodriguez",
                    "3327809", "623852453", "2300299907", "Chase", "XXZ")))
                    .isInstanceOf(InvalidRecipientException.class);
        }

        @Test
        void deletingSomeoneElsesRecipientIsNotFound() {
            UUID id = UUID.randomUUID();
            when(repository.delete(id, USER_ID)).thenReturn(false);

            assertThatThrownBy(() -> service.delete(USER_ID, id)).isInstanceOf(RecipientNotFoundException.class);
        }
    }

    @Nested
    class History {

        private final TransactionHistoryRepository repository = mock(TransactionHistoryRepository.class);
        private final TransactionHistoryService service = new TransactionHistoryService(repository);

        private List<HistoryEntry> entries(int count) {
            return IntStream.range(0, count).mapToObj(i -> new HistoryEntry(100 - i, UUID.randomUUID(),
                    MovementType.WITHDRAWAL, usd("-20.00"), TransferStatus.COMPLETED, "Lucho Rodriguez",
                    NOW.minusSeconds(i))).toList();
        }

        @Test
        void returnsNextCursorOnlyWhenThereIsAnotherPage() {
            when(repository.findPage(USER_ID, null, 3)).thenReturn(entries(3));

            HistoryPage page = service.history(USER_ID, null, 2);

            assertThat(page.items()).hasSize(2);
            assertThat(TransactionHistoryService.decode(page.nextCursor())).isEqualTo(page.items().get(1).cursor());
        }

        @Test
        void lastPageHasNoCursor() {
            when(repository.findPage(USER_ID, null, 3)).thenReturn(entries(1));

            assertThat(service.history(USER_ID, null, 2).nextCursor()).isNull();
        }

        @Test
        void cursorRoundTripsAndIsPassedToTheRepository() {
            HistoryCursor cursor = new HistoryCursor(NOW, 42);
            when(repository.findPage(eq(USER_ID), eq(cursor), eq(21))).thenReturn(List.of());

            assertThat(service.history(USER_ID, TransactionHistoryService.encode(cursor), 20).items()).isEmpty();
        }

        @Test
        void garbageCursorIsRejected() {
            assertThatThrownBy(() -> service.history(USER_ID, "not-a-cursor", 20))
                    .isInstanceOf(InvalidCursorException.class);
        }
    }

    @Nested
    class Reconciliation {

        private final ReconciliationRepository repository = mock(ReconciliationRepository.class);
        private final ReconciliationService service = new ReconciliationService(repository,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofHours(24));

        @Test
        void reportsUnbalancedAndStaleTransfers() {
            UUID broken = UUID.randomUUID();
            when(repository.findClosedTransfersWithUnbalancedTransit()).thenReturn(List.of(broken));
            when(repository.countOpenTransfersNotUpdatedSince(NOW.minus(Duration.ofHours(24))))
                    .thenReturn(Map.of(TransferStatus.IN_REVIEW, 2L));

            ReconciliationReport report = service.reconcile();

            assertThat(report.isClean()).isFalse();
            assertThat(report.unbalancedTransfers()).containsExactly(broken);
            assertThat(report.staleOpenTransfers()).containsEntry(TransferStatus.IN_REVIEW, 2L);
        }

        @Test
        void cleanWhenNothingIsWrong() {
            when(repository.findClosedTransfersWithUnbalancedTransit()).thenReturn(List.of());
            when(repository.countOpenTransfersNotUpdatedSince(any())).thenReturn(Map.of());

            assertThat(service.reconcile().isClean()).isTrue();
        }
    }
}
