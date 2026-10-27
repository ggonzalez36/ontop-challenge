package com.ontop.transfers.application.port.in;

import com.ontop.transfers.application.port.out.ProviderPaymentStatus;
import com.ontop.transfers.domain.model.Transfer;

import java.util.UUID;

/**
 * Applies a payment status reported by the provider (callback). Idempotent: repeating a notification is a no-op.
 */
public interface ApplyPaymentOutcomeUseCase {

    Transfer apply(PaymentNotification notification);

    record PaymentNotification(UUID transferId, String paymentId, ProviderPaymentStatus status, String reason) {
    }
}
