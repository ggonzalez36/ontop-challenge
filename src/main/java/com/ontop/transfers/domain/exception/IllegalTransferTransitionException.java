package com.ontop.transfers.domain.exception;

import com.ontop.transfers.domain.model.TransferStatus;

import java.util.UUID;

/**
 * A programming error: the flow tried to move a transfer to a status that is not reachable from its current one.
 */
public class IllegalTransferTransitionException extends DomainException {

    public IllegalTransferTransitionException(UUID transferId, TransferStatus from, TransferStatus to) {
        super("Transfer " + transferId + " cannot move from " + from + " to " + to);
    }
}
