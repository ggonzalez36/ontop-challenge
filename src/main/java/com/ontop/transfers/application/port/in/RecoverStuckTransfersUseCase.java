package com.ontop.transfers.application.port.in;

public interface RecoverStuckTransfersUseCase {

    RecoveryReport recover();

    /**
     * @param examined transfers looked at in this run
     * @param advanced transfers whose status changed
     * @param waiting  transfers left as they were (provider unavailable, or manual review needed)
     * @param conflicts transfers skipped because another writer changed them or the provider contradicts them
     */
    record RecoveryReport(int examined, int advanced, int waiting, int conflicts) {
    }
}
