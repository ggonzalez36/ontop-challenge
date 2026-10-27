package com.ontop.transfers.application.port.out;

import com.ontop.transfers.domain.model.Transfer;
import com.ontop.transfers.domain.model.TransferStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Stores transfers together with the events they recorded (outbox, ledger, history) in one transaction.
 */
public interface TransferRepository {

    Optional<Transfer> findById(UUID id);

    Optional<Transfer> findByUserIdAndIdempotencyKey(long userId, String idempotencyKey);

    /**
     * Oldest first, at most {@code limit}.
     */
    List<Transfer> findStuck(Set<TransferStatus> statuses, Instant notUpdatedSince, int limit);

    /**
     * @throws com.ontop.transfers.application.exception.TransferInProgressException if the user already has a
     *         transfer in flight or the Idempotency-Key is being used by a concurrent request
     */
    Transfer saveNew(Transfer transfer);

    /**
     * Persists a status change. Returns the stored transfer with its new version.
     *
     * @throws com.ontop.transfers.application.exception.ConcurrentTransferUpdateException if another writer changed
     *         the transfer since it was read
     */
    Transfer save(Transfer transfer);
}
