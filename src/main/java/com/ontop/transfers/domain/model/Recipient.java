package com.ontop.transfers.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * A bank account saved by a user to receive withdrawals.
 */
public record Recipient(UUID id, long userId, String firstName, String lastName, String nationalId,
                        BankAccount bankAccount) {

    public Recipient {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(firstName, "firstName");
        Objects.requireNonNull(lastName, "lastName");
        Objects.requireNonNull(nationalId, "nationalId");
        Objects.requireNonNull(bankAccount, "bankAccount");
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    public RecipientSnapshot snapshot() {
        return new RecipientSnapshot(fullName(), bankAccount.routingNumber(), bankAccount.accountNumber(),
                bankAccount.bankName());
    }
}
