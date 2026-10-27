package com.ontop.transfers.application.exception;

public class WalletUserNotFoundException extends RuntimeException {

    public WalletUserNotFoundException(long userId) {
        super("Wallet not found for user " + userId);
    }
}
