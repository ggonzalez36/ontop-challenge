package com.ontop.transfers.application.exception;

public class WalletOperationRejectedException extends RuntimeException {

    public WalletOperationRejectedException(String reason) {
        super("Wallet rejected the withdrawal: " + reason);
    }
}
