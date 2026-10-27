package com.ontop.transfers.application.port.out;

/**
 * Status of a payment as reported by the provider, by callback or by lookup.
 */
public enum ProviderPaymentStatus {
    PROCESSING,
    COMPLETED,
    FAILED
}
