package com.ontop.transfers.domain.model;

/**
 * Copy of the recipient data at the moment of the transfer, so editing or deleting a saved account never rewrites
 * history.
 */
public record RecipientSnapshot(String fullName, String routingNumber, String accountNumber, String bankName) {

    public String accountLast4() {
        return accountNumber.length() <= 4 ? accountNumber : accountNumber.substring(accountNumber.length() - 4);
    }
}
