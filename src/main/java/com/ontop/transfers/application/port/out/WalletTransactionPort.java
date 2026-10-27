package com.ontop.transfers.application.port.out;

import com.ontop.transfers.domain.model.Money;

public interface WalletTransactionPort {

    WalletOperationResult withdraw(long userId, Money amount);

    WalletOperationResult topUp(long userId, Money amount);
}
