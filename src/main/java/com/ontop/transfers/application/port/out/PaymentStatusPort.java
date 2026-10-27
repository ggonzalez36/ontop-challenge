package com.ontop.transfers.application.port.out;

import java.util.UUID;

/**
 * Looks a payment up at the provider by the external reference we sent (the transfer id). Used by the recovery job
 * to resolve transfers whose payment outcome is unknown.
 */
public interface PaymentStatusPort {

    PaymentLookup lookup(UUID transferId);

    sealed interface PaymentLookup {

        record Found(String paymentId, ProviderPaymentStatus status, String detail) implements PaymentLookup {
        }

        /** The provider answered and has no payment with this reference. */
        record NotFound() implements PaymentLookup {
        }

        /** The provider could not be asked; try again later. */
        record Unavailable(String reason) implements PaymentLookup {
        }
    }
}
