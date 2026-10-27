package com.ontop.transfers.application.exception;

public class InvalidCursorException extends RuntimeException {

    public InvalidCursorException(String cursor) {
        super("Invalid pagination cursor: " + cursor);
    }
}
