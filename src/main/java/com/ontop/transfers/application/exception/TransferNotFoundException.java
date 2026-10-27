package com.ontop.transfers.application.exception;

import java.util.UUID;

public class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException(UUID transferId) {
        super("Transfer " + transferId + " not found");
    }
}
