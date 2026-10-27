package com.ontop.transfers.domain.exception;

/**
 * Base class for violations of business rules.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
