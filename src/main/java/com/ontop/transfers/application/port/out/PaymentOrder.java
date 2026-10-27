package com.ontop.transfers.application.port.out;

import com.ontop.transfers.domain.model.Money;
import com.ontop.transfers.domain.model.RecipientSnapshot;

import java.util.UUID;

/**
 * @param transferId sent to the provider as external reference, so the payment can be reconciled
 */
public record PaymentOrder(UUID transferId, RecipientSnapshot beneficiary, Money amount) {
}
