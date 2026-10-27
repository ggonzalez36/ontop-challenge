package com.ontop.transfers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.ontop.transfers.TestData.SEEDED_RECIPIENT_ID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full slice: HTTP request -> controller -> service -> H2 + real HTTP adapters against a fake of the Ontop mocks
 * (JDK HttpServer bound to loopback).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExecuteTransferIntegrationTest {

    private static final FakeOntopMocks MOCKS = FakeOntopMocks.start();

    @DynamicPropertySource
    static void mockUrls(DynamicPropertyRegistry registry) {
        registry.add("ontop.wallet.base-url", MOCKS::baseUrl);
        registry.add("ontop.payments.base-url", MOCKS::baseUrl);
        registry.add("ontop.payments.read-timeout", () -> "2s");
    }

    @AfterAll
    static void stopMocks() {
        MOCKS.stop();
    }

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void resetMocks() {
        MOCKS.reset();
    }

    private ResponseEntity<Map> postTransfer(String idempotencyKey, String amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", "1000");
        headers.set("Idempotency-Key", idempotencyKey);
        String body = """
                {"recipientId": "%s", "amount": %s, "currency": "USD"}
                """.formatted(SEEDED_RECIPIENT_ID, amount);
        return http.exchange("/v1/transfers", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    void executesTransferAndReplaysTheSameKey() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<Map> created = postTransfer(key, "20.00");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("status", "PROCESSING")
                .containsEntry("fee", 2.0).containsEntry("netAmount", 18.0);
        assertThat(MOCKS.walletAmounts()).containsExactly("-20.00");
        assertThat(MOCKS.paymentBodies()).singleElement().asString().contains("\"amount\":18.00");

        ResponseEntity<Map> replay = postTransfer(key, "20.00");

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).containsEntry("id", created.getBody().get("id"));
        assertThat(MOCKS.walletAmounts()).hasSize(1);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", "1000");
        ResponseEntity<Map> fetched = http.exchange(created.getHeaders().getLocation(), HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        assertThat(fetched.getBody()).containsEntry("status", "PROCESSING");
    }

    @Test
    void providerFailureRefundsTheWallet() {
        MOCKS.failNextPayment();

        ResponseEntity<Map> response = postTransfer(UUID.randomUUID().toString(), "50.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("status", "REFUNDED");
        assertThat(MOCKS.walletAmounts()).containsExactly("-50.00", "50.00");
    }

    @Test
    void insufficientFundsIsRejectedWithoutTouchingTheWallet() {
        ResponseEntity<Map> response = postTransfer(UUID.randomUUID().toString(), "2500.01");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("code", "insufficient-funds");
        assertThat(MOCKS.walletAmounts()).isEmpty();
    }

    /**
     * Minimal stand-in for the Ontop mocks: fixed balance of 2500, wallet transactions always succeed, payments
     * succeed unless told to fail. Records what it receives.
     */
    static final class FakeOntopMocks {

        private final HttpServer server;
        private final List<String> walletAmounts = new CopyOnWriteArrayList<>();
        private final List<String> paymentBodies = new CopyOnWriteArrayList<>();
        private volatile boolean failNextPayment;

        private FakeOntopMocks(HttpServer server) {
            this.server = server;
        }

        static FakeOntopMocks start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                FakeOntopMocks mocks = new FakeOntopMocks(server);
                server.createContext("/wallets/balance",
                        exchange -> respond(exchange, 200, "{\"balance\": 2500, \"user_id\": 1000}"));
                server.createContext("/wallets/transactions", mocks::walletTransaction);
                server.createContext("/api/v1/payments", mocks::payment);
                server.start();
                return mocks;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        void reset() {
            walletAmounts.clear();
            paymentBodies.clear();
            failNextPayment = false;
        }

        void failNextPayment() {
            failNextPayment = true;
        }

        List<String> walletAmounts() {
            return walletAmounts;
        }

        List<String> paymentBodies() {
            return paymentBodies;
        }

        private void walletTransaction(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            walletAmounts.add(body.replaceAll(".*\"amount\":(-?[0-9.]+).*", "$1"));
            respond(exchange, 200, "{\"wallet_transaction_id\": " + (59974 + walletAmounts.size())
                    + ", \"amount\": 0, \"user_id\": 1000}");
        }

        private void payment(HttpExchange exchange) throws IOException {
            paymentBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (failNextPayment) {
                failNextPayment = false;
                respond(exchange, 500, "{\"requestInfo\": {\"status\": \"Failed\", \"error\": \"bank rejected payment\"},"
                        + " \"paymentInfo\": {\"amount\": 45, \"id\": \"f-1\"}}");
                return;
            }
            respond(exchange, 200, "{\"requestInfo\": {\"status\": \"Processing\"},"
                    + " \"paymentInfo\": {\"amount\": 18, \"id\": \"70cfe468-91b9-4e04-8910-5e8257dfadfa\"}}");
        }

        private static void respond(HttpExchange exchange, int status, String json) throws IOException {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }
}
