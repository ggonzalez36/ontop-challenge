package com.ontop.transfers.application.exception;

public class TransferInProgressException extends RuntimeException {

    public TransferInProgressException(long userId) {
        super("User " + userId + " already has a transfer in progress");
    }
}
