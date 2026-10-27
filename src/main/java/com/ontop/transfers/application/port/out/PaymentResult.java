package com.ontop.transfers.application.port.out;

/**
 * Outcome of a payment request. {@link Unknown} means the payment may have been executed (e.g. timeout).
 */
public sealed interface PaymentResult {

    record Accepted(String paymentId, String providerStatus) implements PaymentResult {
    }

    record Rejected(String reason) implements PaymentResult {
    }

    record Unknown(String reason) implements PaymentResult {
    }
}
