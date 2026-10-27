package com.ontop.transfers.adapter.out.persistence;

import com.ontop.transfers.application.exception.ConcurrentTransferUpdateException;
import com.ontop.transfers.application.exception.TransferInProgressException;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository.HistoryEntry;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository.MovementType;
import com.ontop.transfers.domain.model.Recipient;
import com.ontop.transfers.domain.model.ReviewReason;
import com.ontop.transfers.domain.model.Transfer;
import com.ontop.transfers.domain.model.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.ontop.transfers.TestData.SEEDED_RECIPIENT_ID;
import static com.ontop.transfers.TestData.USD;
import static com.ontop.transfers.TestData.USER_ID;
import static com.ontop.transfers.TestData.newTransfer;
import static com.ontop.transfers.TestData.recipient;
import static com.ontop.transfers.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs against the real Flyway schema (H2 in tests, PostgreSQL in {@code PostgresPersistenceIT}). Without a
 * wrapping test transaction, so every save commits like in production and the constraints, optimistic locking and
 * the atomic outbox/ledger/history writes really happen.
 */
@DataJpaTest
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({TransferPersistenceAdapter.class, RecipientPersistenceAdapter.class,
        TransactionHistoryPersistenceAdapter.class, ReconciliationPersistenceAdapter.class, OutboxStore.class,
        PiiCipher.class, TransferPersistenceAdapterTest.ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransferPersistenceAdapterTest {

    private static final AtomicLong USERS = new AtomicLong(50_000);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Autowired
    private TransferPersistenceAdapter transfers;
    @Autowired
    private RecipientPersistenceAdapter recipients;
    @Autowired
    private TransactionHistoryPersistenceAdapter history;
    @Autowired
    private ReconciliationPersistenceAdapter reconciliation;
    @Autowired
    private SpringDataOutboxRepository outbox;
    @Autowired
    private SpringDataLedgerRepository ledger;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedRecipient() {
        if (recipients.findByIdAndUserId(SEEDED_RECIPIENT_ID, USER_ID).isEmpty()) {
            recipients.save(recipient());
        }
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private Transfer completed(long userId) {
        Transfer transfer = transfers.saveNew(newTransfer(userId, "20.00", key()));
        transfer.markWalletDebited("59974");
        transfer = transfers.save(transfer);
        transfer.markPaymentSubmitted("pay-1");
        transfer = transfers.save(transfer);
        transfer.markCompleted();
        return transfers.save(transfer);
    }

    private Transfer refunded(long userId, String amount) {
        Transfer transfer = transfers.saveNew(newTransfer(userId, amount, key()));
        transfer.markWalletDebited("59974");
        transfer.markPaymentFailed("bank rejected");
        transfer = transfers.save(transfer);
        transfer.markRefunded("59975");
        return transfers.save(transfer);
    }

    @Test
    void roundTripKeepsAllFields() {
        long userId = USERS.incrementAndGet();
        Transfer saved = transfers.saveNew(newTransfer(userId, "20.00", "k-roundtrip-" + userId));
        saved.markWalletDebited("59974");
        saved.markOutcomeUnknown(ReviewReason.PAYMENT_UNKNOWN, "timeout");
        transfers.save(saved);

        Transfer loaded = transfers.findById(saved.id()).orElseThrow();

        assertThat(loaded.status()).isEqualTo(TransferStatus.IN_REVIEW);
        assertThat(loaded.reviewReason()).isEqualTo(ReviewReason.PAYMENT_UNKNOWN);
        assertThat(loaded.amount()).isEqualTo(usd("20.00"));
        assertThat(loaded.fee()).isEqualTo(usd("2.00"));
        assertThat(loaded.netAmount()).isEqualTo(usd("18.00"));
        assertThat(loaded.walletDebitId()).isEqualTo("59974");
        assertThat(loaded.recipient().accountNumber()).isEqualTo("1885226711");
        assertThat(loaded.idempotencyKey().value()).isEqualTo("k-roundtrip-" + userId);
        assertThat(loaded.version()).isEqualTo(1L);
    }

    @Test
    void sensitiveNumbersAreEncryptedAtRest() {
        String storedAccount = jdbc.queryForObject("select account_number from recipient where id = ?", String.class,
                SEEDED_RECIPIENT_ID);
        String storedNationalId = jdbc.queryForObject("select national_id from recipient where id = ?", String.class,
                SEEDED_RECIPIENT_ID);

        assertThat(storedAccount).isNotEqualTo("1885226711").doesNotContain("1885226711");
        assertThat(storedNationalId).doesNotContain("3327809");
        assertThat(recipients.findByIdAndUserId(SEEDED_RECIPIENT_ID, USER_ID).orElseThrow()
                .bankAccount().accountNumber()).isEqualTo("1885226711");
    }

    @Test
    void stateChangesWriteOutboxLedgerAndHistoryInTheSameTransaction() {
        Transfer transfer = completed(USERS.incrementAndGet());

        assertThat(outbox.findByAggregateIdOrderByOccurredAtAsc(transfer.id()))
                .extracting(OutboxEventEntity::getEventType)
                .containsExactlyInAnyOrder("TransferCreated", "WalletDebited", "PaymentSubmitted", "TransferCompleted");
        assertThat(ledger.findByTransferIdOrderByIdAsc(transfer.id()))
                .extracting(LedgerEntryEntity::getAccount)
                .containsExactly("user_wallet:" + transfer.userId(), "transfers_in_transit",
                        "transfers_in_transit", "ontop_bank", "fee_revenue");
        assertThat(history.findPage(transfer.userId(), null, 10))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.type()).isEqualTo(MovementType.WITHDRAWAL);
                    assertThat(entry.amount()).isEqualTo(usd("-20.00"));
                    assertThat(entry.transferStatus()).isEqualTo(TransferStatus.COMPLETED);
                });
        assertThat(reconciliation.findClosedTransfersWithUnbalancedTransit()).doesNotContain(transfer.id());
    }

    @Test
    void reconciliationFlagsAClosedTransferWithMissingPostings() {
        Transfer transfer = completed(USERS.incrementAndGet());
        jdbc.update("delete from ledger_entry where transfer_id = ? and debit > 0 and account = 'transfers_in_transit'",
                transfer.id());

        assertThat(reconciliation.findClosedTransfersWithUnbalancedTransit()).contains(transfer.id());
    }

    @Test
    void historyIsNewestFirstWithKeysetPagination() {
        long userId = USERS.incrementAndGet();
        refunded(userId, "10.00");
        refunded(userId, "30.00");

        List<HistoryEntry> firstPage = history.findPage(userId, null, 3);
        List<HistoryEntry> secondPage = history.findPage(userId, firstPage.getLast().cursor(), 3);

        assertThat(firstPage).extracting(HistoryEntry::type)
                .containsExactly(MovementType.REFUND, MovementType.WITHDRAWAL, MovementType.REFUND);
        assertThat(firstPage.getFirst().amount()).isEqualTo(usd("30.00"));
        assertThat(secondPage).singleElement().extracting(HistoryEntry::amount).isEqualTo(usd("-10.00"));
    }

    @Test
    void findsStuckTransfersOldestFirst() {
        Transfer stuck = transfers.saveNew(newTransfer(USERS.incrementAndGet(), "20.00", key()));

        assertThat(transfers.findStuck(EnumSet.of(TransferStatus.CREATED), Instant.now().plusSeconds(60), 1000))
                .extracting(Transfer::id).contains(stuck.id());
        assertThat(transfers.findStuck(EnumSet.of(TransferStatus.CREATED), Instant.now().minusSeconds(60), 1000))
                .extracting(Transfer::id).doesNotContain(stuck.id());
        assertThat(reconciliation.countOpenTransfersNotUpdatedSince(Instant.now().plusSeconds(60)))
                .containsKey(TransferStatus.CREATED);
    }

    @Test
    void secondInFlightTransferOfSameUserIsRejected() {
        long userId = USERS.incrementAndGet();
        transfers.saveNew(newTransfer(userId, "20.00", key()));

        assertThatThrownBy(() -> transfers.saveNew(newTransfer(userId, "30.00", key())))
                .isInstanceOf(TransferInProgressException.class);
    }

    @Test
    void newTransferIsAllowedOnceThePreviousLeftTheInFlightStates() {
        long userId = USERS.incrementAndGet();
        completed(userId);

        Transfer second = transfers.saveNew(newTransfer(userId, "30.00", key()));

        assertThat(second.status()).isEqualTo(TransferStatus.CREATED);
    }

    @Test
    void reusedIdempotencyKeyIsRejected() {
        long userId = USERS.incrementAndGet();
        String key = key();
        Transfer first = transfers.saveNew(newTransfer(userId, "20.00", key));
        first.markWalletDebited("59974");
        first.markPaymentSubmitted("pay-1");
        transfers.save(first);

        assertThatThrownBy(() -> transfers.saveNew(newTransfer(userId, "20.00", key)))
                .isInstanceOf(TransferInProgressException.class);
    }

    @Test
    void staleWriteFailsWithOptimisticLocking() {
        Transfer saved = transfers.saveNew(newTransfer(USERS.incrementAndGet(), "20.00", key()));
        Transfer copyA = transfers.findById(saved.id()).orElseThrow();
        Transfer copyB = transfers.findById(saved.id()).orElseThrow();

        copyA.markWalletDebited("59974");
        transfers.save(copyA);
        copyB.markOutcomeUnknown(ReviewReason.INTERRUPTED, "recovery job");

        assertThatThrownBy(() -> transfers.save(copyB)).isInstanceOf(ConcurrentTransferUpdateException.class);
        assertThat(outbox.findByAggregateIdOrderByOccurredAtAsc(saved.id()))
                .extracting(OutboxEventEntity::getEventType).doesNotContain("TransferSentToReview");
    }

    @Test
    void recipientsAreScopedToTheirOwnerAndSoftDeleted() {
        long userId = USERS.incrementAndGet();
        Recipient created = recipients.save(Recipient.create(userId, "Ana", "Gomez", "AB-1234", "623852453",
                "2300299907", "Chase", USD));

        assertThat(recipients.findByUserId(userId)).extracting(Recipient::id).containsExactly(created.id());
        assertThat(recipients.delete(created.id(), userId + 1)).isFalse();
        assertThat(recipients.delete(created.id(), userId)).isTrue();
        assertThat(recipients.findByUserId(userId)).isEmpty();
        assertThat(recipients.findByIdAndUserId(SEEDED_RECIPIENT_ID, 2000)).isEmpty();
    }
}
