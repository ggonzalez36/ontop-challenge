package com.ontop.transfers.application.exception;

public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("Idempotency-Key " + idempotencyKey + " was already used with a different request");
    }
}
