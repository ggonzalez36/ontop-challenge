package com.ontop.transfers.application.port.in;

import com.ontop.transfers.domain.model.Transfer;

/**
 * Result of executing a transfer. {@code replayed} is true when the Idempotency-Key was already used with the same
 * request and no side effect happened.
 */
public record TransferOutcome(Transfer transfer, boolean replayed) {

    public static TransferOutcome executed(Transfer transfer) {
        return new TransferOutcome(transfer, false);
    }

    public static TransferOutcome replayed(Transfer transfer) {
        return new TransferOutcome(transfer, true);
    }
}
