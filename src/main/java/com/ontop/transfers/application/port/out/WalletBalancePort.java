package com.ontop.transfers.application.port.out;

import com.ontop.transfers.domain.model.Money;

public interface WalletBalancePort {

    /**
     * @throws com.ontop.transfers.application.exception.WalletUserNotFoundException if the wallet does not exist
     * @throws com.ontop.transfers.application.exception.WalletUnavailableException if the wallet service fails
     */
    Money getBalance(long userId);
}
