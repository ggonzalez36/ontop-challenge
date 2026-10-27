package com.ontop.transfers.domain.model;

import java.util.Currency;
import java.util.Objects;

public record BankAccount(String routingNumber, String accountNumber, String bankName, Currency currency) {

    public BankAccount {
        Objects.requireNonNull(routingNumber, "routingNumber");
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(bankName, "bankName");
        Objects.requireNonNull(currency, "currency");
    }
}
