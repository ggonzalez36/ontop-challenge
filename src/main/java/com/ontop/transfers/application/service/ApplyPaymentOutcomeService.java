package com.ontop.transfers.application.service;

import com.ontop.transfers.application.exception.PaymentOutcomeConflictException;
import com.ontop.transfers.application.exception.TransferNotFoundException;
import com.ontop.transfers.application.port.in.ApplyPaymentOutcomeUseCase;
import com.ontop.transfers.application.port.out.TransferRepository;
import com.ontop.transfers.domain.model.Transfer;

public final class ApplyPaymentOutcomeService implements ApplyPaymentOutcomeUseCase {

    private final TransferRepository transfers;
    private final PaymentOutcomeHandler outcomes;

    public ApplyPaymentOutcomeService(TransferRepository transfers, PaymentOutcomeHandler outcomes) {
        this.transfers = transfers;
        this.outcomes = outcomes;
    }

    @Override
    public Transfer apply(PaymentNotification notification) {
        Transfer transfer = transfers.findById(notification.transferId())
                .orElseThrow(() -> new TransferNotFoundException(notification.transferId()));
        if (transfer.providerPaymentId() != null && !transfer.providerPaymentId().equals(notification.paymentId())) {
            throw new PaymentOutcomeConflictException(transfer.id(), "payment id " + notification.paymentId()
                    + " does not match " + transfer.providerPaymentId());
        }
        return outcomes.apply(transfer, notification.paymentId(), notification.status(), notification.reason());
    }
}
