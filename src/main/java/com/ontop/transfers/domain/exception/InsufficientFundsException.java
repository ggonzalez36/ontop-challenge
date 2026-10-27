package com.ontop.transfers.domain.exception;

import com.ontop.transfers.domain.model.Money;

public class InsufficientFundsException extends DomainException {

    public InsufficientFundsException(Money requested, Money available) {
        super("Insufficient wallet funds: requested " + requested + ", available " + available);
    }
}
