package com.ontop.transfers.adapter.in.web;

import com.ontop.transfers.application.exception.IdempotencyKeyConflictException;
import com.ontop.transfers.application.exception.RecipientNotFoundException;
import com.ontop.transfers.application.exception.TransferInProgressException;
import com.ontop.transfers.application.exception.TransferNotFoundException;
import com.ontop.transfers.application.exception.WalletOperationRejectedException;
import com.ontop.transfers.application.exception.WalletUnavailableException;
import com.ontop.transfers.application.exception.WalletUserNotFoundException;
import com.ontop.transfers.domain.exception.InsufficientFundsException;
import com.ontop.transfers.domain.exception.InvalidAmountException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 9457 problem details. Every body carries a stable {@code code} the client can switch on.
 * Spring MVC binding and validation errors are handled by the parent class (400).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String RETRY_AFTER_SECONDS = "5";

    @ExceptionHandler(InvalidAmountException.class)
    ProblemDetail invalidAmount(InvalidAmountException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-amount", e.getMessage());
    }

    @ExceptionHandler(RecipientNotFoundException.class)
    ProblemDetail recipientNotFound(RecipientNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "recipient-not-found", e.getMessage());
    }

    @ExceptionHandler(TransferNotFoundException.class)
    ProblemDetail transferNotFound(TransferNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "transfer-not-found", e.getMessage());
    }

    @ExceptionHandler(WalletUserNotFoundException.class)
    ProblemDetail walletNotFound(WalletUserNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "wallet-not-found", e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-funds", e.getMessage());
    }

    @ExceptionHandler(WalletOperationRejectedException.class)
    ProblemDetail walletRejected(WalletOperationRejectedException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-rejected", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyKeyConflictException e) {
        return problem(HttpStatus.CONFLICT, "idempotency-key-conflict", e.getMessage());
    }

    @ExceptionHandler(TransferInProgressException.class)
    ProblemDetail transferInProgress(TransferInProgressException e) {
        return problem(HttpStatus.CONFLICT, "transfer-in-progress", e.getMessage());
    }

    @ExceptionHandler(WalletUnavailableException.class)
    ResponseEntity<ProblemDetail> walletUnavailable(WalletUnavailableException e) {
        log.warn("Wallet service unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "wallet-unavailable",
                        "Wallet service is temporarily unavailable, please retry"));
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unexpected error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Unexpected error");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return problem;
    }
}
