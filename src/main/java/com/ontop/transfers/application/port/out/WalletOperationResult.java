package com.ontop.transfers.application.port.out;

/**
 * Outcome of a wallet write. {@link Unknown} means the balance may or may not have changed.
 */
public sealed interface WalletOperationResult {

    record Succeeded(String walletTransactionId) implements WalletOperationResult {
    }

    record Rejected(String reason) implements WalletOperationResult {
    }

    record Unknown(String reason) implements WalletOperationResult {
    }
}
