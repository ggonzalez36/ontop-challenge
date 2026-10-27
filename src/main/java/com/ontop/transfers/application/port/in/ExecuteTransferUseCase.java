package com.ontop.transfers.application.port.in;

public interface ExecuteTransferUseCase {

    TransferOutcome execute(ExecuteTransferCommand command);
}
