package com.ontop.transfers.application.port.in;

import com.ontop.transfers.domain.model.Transfer;

import java.util.UUID;

public interface GetTransferUseCase {

    Transfer get(long userId, UUID transferId);
}
