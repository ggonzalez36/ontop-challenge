package com.ontop.transfers.application.exception;

import java.util.UUID;

/**
 * The provider reports something incompatible with what we know (e.g. "completed" for a transfer we already refunded).
 * Never resolved automatically.
 */
public class PaymentOutcomeConflictException extends RuntimeException {

    public PaymentOutcomeConflictException(UUID transferId, String detail) {
        super("Payment outcome for transfer " + transferId + " conflicts with its state: " + detail);
    }
}
