package com.ontop.transfers.adapter.out.wallet;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.ontop.transfers.application.exception.WalletUnavailableException;
import com.ontop.transfers.application.exception.WalletUserNotFoundException;
import com.ontop.transfers.application.port.out.WalletBalancePort;
import com.ontop.transfers.application.port.out.WalletOperationResult;
import com.ontop.transfers.application.port.out.WalletTransactionPort;
import com.ontop.transfers.domain.model.Money;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Client for the wallet balance and wallet transactions APIs. A withdrawal is a transaction with a negative amount
 * and a top-up (refund) one with a positive amount. Calls go through a circuit breaker so a failing wallet service
 * is not hammered; an open circuit means the call was not sent.
 */
public class WalletHttpAdapter implements WalletBalancePort, WalletTransactionPort {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Currency walletCurrency;

    public WalletHttpAdapter(RestClient restClient, CircuitBreaker circuitBreaker, Currency walletCurrency) {
        this.restClient = restClient;
        this.circuitBreaker = circuitBreaker;
        this.walletCurrency = walletCurrency;
    }

    @Override
    public Money getBalance(long userId) {
        try {
            BalanceResponse response = circuitBreaker.executeSupplier(() -> restClient.get()
                    .uri("/wallets/balance?user_id={userId}", userId)
                    .retrieve()
                    .body(BalanceResponse.class));
            if (response == null || response.balance() == null) {
                throw new WalletUnavailableException("Wallet balance response without balance");
            }
            return Money.of(response.balance(), walletCurrency);
        } catch (CallNotPermittedException e) {
            throw new WalletUnavailableException("Wallet circuit breaker is open");
        } catch (HttpClientErrorException.NotFound e) {
            throw new WalletUserNotFoundException(userId);
        } catch (RestClientResponseException e) {
            throw new WalletUnavailableException("Wallet balance returned HTTP " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            throw new WalletUnavailableException("Wallet balance unreachable: " + e.getMessage());
        }
    }

    @Override
    public WalletOperationResult withdraw(long userId, Money amount) {
        return createTransaction(userId, amount.negate());
    }

    @Override
    public WalletOperationResult topUp(long userId, Money amount) {
        return createTransaction(userId, amount);
    }

    /**
     * 4xx means the wallet refused the operation and the balance did not change; so does an open circuit (nothing
     * was sent). A 5xx or a network error may have happened after the balance changed, so the outcome is unknown
     * and must not be retried blindly.
     */
    private WalletOperationResult createTransaction(long userId, Money signedAmount) {
        try {
            WalletTransactionResponse response = circuitBreaker.executeSupplier(() -> restClient.post()
                    .uri("/wallets/transactions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new WalletTransactionRequest(userId, signedAmount.amount()))
                    .retrieve()
                    .body(WalletTransactionResponse.class));
            if (response == null || response.walletTransactionId() == null) {
                return new WalletOperationResult.Unknown("Wallet response without transaction id");
            }
            return new WalletOperationResult.Succeeded(String.valueOf(response.walletTransactionId()));
        } catch (CallNotPermittedException e) {
            return new WalletOperationResult.Rejected("Wallet circuit breaker is open, operation not sent");
        } catch (HttpClientErrorException e) {
            return new WalletOperationResult.Rejected("HTTP " + e.getStatusCode().value() + " " + errorMessage(e));
        } catch (HttpServerErrorException e) {
            return new WalletOperationResult.Unknown("HTTP " + e.getStatusCode().value() + " " + errorMessage(e));
        } catch (ResourceAccessException e) {
            return new WalletOperationResult.Unknown(e.getMessage());
        }
    }

    private static String errorMessage(RestClientResponseException e) {
        try {
            WalletError error = e.getResponseBodyAs(WalletError.class);
            return error != null && error.message() != null ? error.message() : e.getStatusText();
        } catch (RuntimeException parseFailure) {
            return e.getStatusText();
        }
    }

    record BalanceResponse(BigDecimal balance, @JsonProperty("user_id") Long userId) {
    }

    record WalletTransactionRequest(@JsonProperty("user_id") long userId, BigDecimal amount) {
    }

    record WalletTransactionResponse(@JsonProperty("wallet_transaction_id") Long walletTransactionId,
                                     BigDecimal amount, @JsonProperty("user_id") Long userId) {
    }

    record WalletError(String code, String message) {
    }
}
