package com.ontop.transfers.application.exception;

import java.util.UUID;

public class ConcurrentTransferUpdateException extends RuntimeException {

    public ConcurrentTransferUpdateException(UUID transferId) {
        super("Transfer " + transferId + " was modified concurrently, retry");
    }
}
