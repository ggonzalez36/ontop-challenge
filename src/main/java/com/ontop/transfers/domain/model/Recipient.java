package com.ontop.transfers.domain.model;

import com.ontop.transfers.domain.exception.InvalidRecipientException;

import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A bank account saved by a user to receive withdrawals.
 */
public record Recipient(UUID id, long userId, String firstName, String lastName, String nationalId,
                        BankAccount bankAccount) {

    private static final Pattern ROUTING_NUMBER = Pattern.compile("\\d{9}");
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("\\d{4,17}");
    private static final Pattern NATIONAL_ID = Pattern.compile("[A-Za-z0-9-]{4,50}");
    private static final int MAX_TEXT = 100;

    public Recipient {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(firstName, "firstName");
        Objects.requireNonNull(lastName, "lastName");
        Objects.requireNonNull(nationalId, "nationalId");
        Objects.requireNonNull(bankAccount, "bankAccount");
    }

    /**
     * Validates the data entered on the "recipient's bank account" screen. Routing numbers are checked for format
     * only: the examples in the challenge do not pass the ABA checksum (see docs/open-questions.md).
     */
    public static Recipient create(long userId, String firstName, String lastName, String nationalId,
                                   String routingNumber, String accountNumber, String bankName, Currency currency) {
        requireText(firstName, "First name");
        requireText(lastName, "Last name");
        requireText(bankName, "Bank name");
        requireFormat(nationalId, NATIONAL_ID, "National identification number must be 4-50 letters, digits or dashes");
        requireFormat(routingNumber, ROUTING_NUMBER, "Routing number must have 9 digits");
        requireFormat(accountNumber, ACCOUNT_NUMBER, "Account number must have 4 to 17 digits");
        if (currency == null) {
            throw new InvalidRecipientException("Currency is required");
        }
        return new Recipient(UUID.randomUUID(), userId, firstName.strip(), lastName.strip(), nationalId,
                new BankAccount(routingNumber, accountNumber, bankName.strip(), currency));
    }

    public String fullName() {
        return firstName + " " + lastName;
    }

    public RecipientSnapshot snapshot() {
        return new RecipientSnapshot(fullName(), bankAccount.routingNumber(), bankAccount.accountNumber(),
                bankAccount.bankName());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT) {
            throw new InvalidRecipientException(field + " is required and must have at most " + MAX_TEXT + " characters");
        }
    }

    private static void requireFormat(String value, Pattern pattern, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new InvalidRecipientException(message);
        }
    }
}
