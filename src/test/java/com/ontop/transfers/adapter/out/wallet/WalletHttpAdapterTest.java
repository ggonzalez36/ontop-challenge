package com.ontop.transfers.adapter.out.wallet;

import com.ontop.transfers.application.exception.WalletUnavailableException;
import com.ontop.transfers.application.exception.WalletUserNotFoundException;
import com.ontop.transfers.application.port.out.WalletOperationResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static com.ontop.transfers.TestData.USD;
import static com.ontop.transfers.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class WalletHttpAdapterTest {

    private static final String BASE_URL = "http://wallet.test";

    private MockRestServiceServer server;
    private WalletHttpAdapter adapter;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        circuitBreaker = CircuitBreaker.ofDefaults("wallet");
        adapter = new WalletHttpAdapter(builder.build(), circuitBreaker, USD);
    }

    @Test
    void readsBalance() {
        server.expect(requestTo(BASE_URL + "/wallets/balance?user_id=1000"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"balance\": 2500, \"user_id\": 1000}", MediaType.APPLICATION_JSON));

        assertThat(adapter.getBalance(1000)).isEqualTo(usd("2500.00"));
        server.verify();
    }

    @Test
    void unknownWalletUserIsNotFound() {
        server.expect(requestTo(BASE_URL + "/wallets/balance?user_id=404"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> adapter.getBalance(404)).isInstanceOf(WalletUserNotFoundException.class);
    }

    @Test
    void balanceServerErrorIsUnavailable() {
        server.expect(requestTo(BASE_URL + "/wallets/balance?user_id=1000")).andRespond(withServerError());

        assertThatThrownBy(() -> adapter.getBalance(1000)).isInstanceOf(WalletUnavailableException.class);
    }

    @Test
    void balanceTimeoutIsUnavailable() {
        server.expect(requestTo(BASE_URL + "/wallets/balance?user_id=1000"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> adapter.getBalance(1000)).isInstanceOf(WalletUnavailableException.class);
    }

    @Test
    void withdrawSendsNegativeAmount() {
        server.expect(requestTo(BASE_URL + "/wallets/transactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"user_id\": 1000, \"amount\": -20.00}", true))
                .andRespond(withSuccess("{\"wallet_transaction_id\": 59974, \"amount\": -20, \"user_id\": 1000}",
                        MediaType.APPLICATION_JSON));

        assertThat(adapter.withdraw(1000, usd("20.00"))).isEqualTo(new WalletOperationResult.Succeeded("59974"));
        server.verify();
    }

    @Test
    void topUpSendsPositiveAmount() {
        server.expect(requestTo(BASE_URL + "/wallets/transactions"))
                .andExpect(content().json("{\"user_id\": 1000, \"amount\": 20.00}", true))
                .andRespond(withSuccess("{\"wallet_transaction_id\": 59975, \"amount\": 20, \"user_id\": 1000}",
                        MediaType.APPLICATION_JSON));

        assertThat(adapter.topUp(1000, usd("20.00"))).isEqualTo(new WalletOperationResult.Succeeded("59975"));
    }

    @Test
    void clientErrorIsRejectedWithMessage() {
        server.expect(requestTo(BASE_URL + "/wallets/transactions"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\": \"INVALID_BODY\", \"message\": \"amount and user_id must not be null\"}"));

        assertThat(adapter.withdraw(1000, usd("20.00")))
                .isEqualTo(new WalletOperationResult.Rejected("HTTP 400 amount and user_id must not be null"));
    }

    @Test
    void serverErrorIsUnknown() {
        server.expect(requestTo(BASE_URL + "/wallets/transactions")).andRespond(withServerError());

        assertThat(adapter.withdraw(1000, usd("20.00"))).isInstanceOf(WalletOperationResult.Unknown.class);
    }

    @Test
    void timeoutIsUnknown() {
        server.expect(requestTo(BASE_URL + "/wallets/transactions"))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThat(adapter.withdraw(1000, usd("20.00"))).isInstanceOf(WalletOperationResult.Unknown.class);
    }

    @Test
    void openCircuitFailsFastWithoutCallingTheWallet() {
        circuitBreaker.transitionToOpenState();

        assertThatThrownBy(() -> adapter.getBalance(1000))
                .isInstanceOf(WalletUnavailableException.class)
                .hasMessageContaining("circuit breaker is open");
        assertThat(adapter.withdraw(1000, usd("20.00"))).isInstanceOf(WalletOperationResult.Rejected.class);
        server.verify();
    }
}
