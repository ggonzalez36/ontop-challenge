package com.ontop.transfers.application.service;

import com.ontop.transfers.application.exception.TransferNotFoundException;
import com.ontop.transfers.application.port.in.GetTransferUseCase;
import com.ontop.transfers.application.port.out.TransferRepository;
import com.ontop.transfers.domain.model.Transfer;

import java.util.UUID;

public final class GetTransferService implements GetTransferUseCase {

    private final TransferRepository transfers;

    public GetTransferService(TransferRepository transfers) {
        this.transfers = transfers;
    }

    /**
     * A transfer of another user is reported as not found, so ids cannot be probed.
     */
    @Override
    public Transfer get(long userId, UUID transferId) {
        return transfers.findById(transferId)
                .filter(transfer -> transfer.userId() == userId)
                .orElseThrow(() -> new TransferNotFoundException(transferId));
    }
}
