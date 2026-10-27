package com.ontop.transfers.application.exception;

public class WalletUnavailableException extends RuntimeException {

    public WalletUnavailableException(String message) {
        super(message);
    }
}
