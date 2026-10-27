package com.ontop.transfers.application.exception;

import java.util.UUID;

public class RecipientNotFoundException extends RuntimeException {

    public RecipientNotFoundException(UUID recipientId) {
        super("Recipient " + recipientId + " not found");
    }
}
