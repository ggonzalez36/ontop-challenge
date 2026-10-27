package com.ontop.transfers.adapter.out.persistence;

import com.ontop.transfers.application.exception.ConcurrentTransferUpdateException;
import com.ontop.transfers.application.exception.TransferInProgressException;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository.MovementType;
import com.ontop.transfers.application.port.out.TransferRepository;
import com.ontop.transfers.domain.event.TransferEvent;
import com.ontop.transfers.domain.ledger.LedgerEntry;
import com.ontop.transfers.domain.ledger.LedgerPostings;
import com.ontop.transfers.domain.model.IdempotencyKey;
import com.ontop.transfers.domain.model.Money;
import com.ontop.transfers.domain.model.RecipientSnapshot;
import com.ontop.transfers.domain.model.Transfer;
import com.ontop.transfers.domain.model.TransferState;
import com.ontop.transfers.domain.model.TransferStatus;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Each write is one short transaction that stores the transfer and, atomically, what its events imply: outbox rows,
 * ledger postings and history movements. No transaction spans a remote call.
 */
@Component
class TransferPersistenceAdapter implements TransferRepository {

    private final SpringDataTransferRepository repository;
    private final SpringDataLedgerRepository ledger;
    private final SpringDataWalletMovementRepository movements;
    private final OutboxStore outbox;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    TransferPersistenceAdapter(SpringDataTransferRepository repository, SpringDataLedgerRepository ledger,
                               SpringDataWalletMovementRepository movements, OutboxStore outbox,
                               ApplicationEventPublisher eventPublisher, Clock clock) {
        this.repository = repository;
        this.ledger = ledger;
        this.movements = movements;
        this.outbox = outbox;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Override
    public Optional<Transfer> findById(UUID id) {
        return repository.findById(id).map(TransferPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<Transfer> findByUserIdAndIdempotencyKey(long userId, String idempotencyKey) {
        return repository.findByUserIdAndIdempotencyKey(userId, idempotencyKey).map(TransferPersistenceAdapter::toDomain);
    }

    @Override
    public List<Transfer> findStuck(Set<TransferStatus> statuses, Instant notUpdatedSince, int limit) {
        return repository.findByStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(statuses, notUpdatedSince, Limit.of(limit))
                .stream().map(TransferPersistenceAdapter::toDomain).toList();
    }

    /**
     * Unique constraints on (user_id, idempotency_key) and on in_flight_user_id turn a concurrent duplicate or a
     * second in-flight transfer of the same user into a conflict.
     */
    @Override
    @Transactional
    public Transfer saveNew(Transfer transfer) {
        List<TransferEvent> events = transfer.pullEvents();
        TransferJpaEntity saved;
        try {
            saved = repository.saveAndFlush(toEntity(transfer));
        } catch (DataIntegrityViolationException e) {
            throw new TransferInProgressException(transfer.userId());
        }
        record(events);
        return toDomain(saved);
    }

    /**
     * Optimistic locking: if another writer (request, callback, recovery job) changed the transfer since it was read,
     * the version check fails and nothing is written.
     */
    @Override
    @Transactional
    public Transfer save(Transfer transfer) {
        List<TransferEvent> events = transfer.pullEvents();
        TransferJpaEntity saved;
        try {
            saved = repository.saveAndFlush(toEntity(transfer));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ConcurrentTransferUpdateException(transfer.id());
        }
        record(events);
        return toDomain(saved);
    }

    private void record(List<TransferEvent> events) {
        Instant now = clock.instant();
        for (TransferEvent event : events) {
            outbox.append(event, now);
            for (LedgerEntry entry : LedgerPostings.forEvent(event)) {
                ledger.save(new LedgerEntryEntity(entry.transferId(), entry.account(), entry.debit().amount(),
                        entry.credit().amount(), entry.debit().currency().getCurrencyCode(), now));
            }
            movementFor(event, now).ifPresent(movements::save);
            eventPublisher.publishEvent(event);
        }
    }

    private static Optional<WalletMovementEntity> movementFor(TransferEvent event, Instant now) {
        return switch (event) {
            case TransferEvent.WalletDebited e -> Optional.of(movement(e.userId(), e.transferId(),
                    MovementType.WITHDRAWAL, e.amount().negate(), now));
            case TransferEvent.TransferRefunded e -> Optional.of(movement(e.userId(), e.transferId(),
                    MovementType.REFUND, e.amount(), now));
            default -> Optional.empty();
        };
    }

    private static WalletMovementEntity movement(long userId, UUID transferId, MovementType type, Money amount,
                                                 Instant now) {
        return new WalletMovementEntity(userId, transferId, type, amount.amount(),
                amount.currency().getCurrencyCode(), now);
    }

    private static TransferJpaEntity toEntity(Transfer t) {
        return new TransferJpaEntity(t.id(), t.userId(), t.recipientId(), t.recipient().fullName(),
                t.recipient().routingNumber(), t.recipient().accountNumber(), t.recipient().bankName(),
                t.idempotencyKey().value(), t.idempotencyKey().requestFingerprint(), t.amount().amount(),
                t.fee().amount(), t.netAmount().amount(), t.amount().currency().getCurrencyCode(), t.status(),
                t.reviewReason(), t.walletDebitId(), t.walletRefundId(), t.providerPaymentId(), t.failureReason(),
                t.createdAt(), t.version());
    }

    private static Transfer toDomain(TransferJpaEntity e) {
        String currency = e.getCurrency();
        return Transfer.restore(new TransferState(
                e.getId(),
                e.getUserId(),
                e.getRecipientId(),
                new RecipientSnapshot(e.getRecipientName(), e.getRecipientRoutingNumber(),
                        e.getRecipientAccountNumber(), e.getRecipientBankName()),
                Money.of(e.getAmount(), currency),
                Money.of(e.getFee(), currency),
                Money.of(e.getNetAmount(), currency),
                new IdempotencyKey(e.getIdempotencyKey(), e.getRequestFingerprint()),
                e.getStatus(),
                e.getReviewReason(),
                e.getWalletDebitId(),
                e.getWalletRefundId(),
                e.getProviderPaymentId(),
                e.getFailureReason(),
                e.getCreatedAt(),
                e.getVersion()));
    }
}
