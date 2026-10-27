package com.ontop.transfers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.ontop.transfers.TestData.SEEDED_RECIPIENT_ID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full slice: HTTP request with a real JWT -> controllers -> use cases -> H2 + real HTTP adapters against a fake of
 * the Ontop mocks (JDK HttpServer bound to loopback). Scheduled jobs are off; the tests drive everything.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "ontop.jobs.enabled=false")
class ExecuteTransferIntegrationTest {

    private static final FakeOntopMocks MOCKS = FakeOntopMocks.start();
    private static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {
    };
    private static final String WEBHOOK_SECRET = "dev-only-payments-webhook-secret";

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

    @Autowired
    private MeterRegistry meters;

    @BeforeEach
    void resetMocks() {
        MOCKS.reset();
    }

    private static HttpHeaders authenticated() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(TestTokens.forUser(1000));
        return headers;
    }

    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String path, String body, HttpHeaders headers) {
        return http.exchange(path, method, new HttpEntity<>(body, headers), JSON);
    }

    private ResponseEntity<Map<String, Object>> postTransfer(String idempotencyKey, String amount) {
        HttpHeaders headers = authenticated();
        headers.set("Idempotency-Key", idempotencyKey);
        return call(HttpMethod.POST, "/v1/transfers", """
                {"recipientId": "%s", "amount": %s, "currency": "USD"}
                """.formatted(SEEDED_RECIPIENT_ID, amount), headers);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> historyOf(Object transferId) {
        ResponseEntity<Map<String, Object>> response = call(HttpMethod.GET, "/v1/transactions?limit=50", null,
                authenticated());
        return ((List<Map<String, Object>>) response.getBody().get("items")).stream()
                .filter(item -> transferId.equals(item.get("transferId")))
                .toList();
    }

    private double eventCount(String type) {
        var counter = meters.find("ontop.transfers.events").tag("type", type).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void requestsWithoutTokenAreRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        assertThat(call(HttpMethod.GET, "/v1/recipients", null, headers).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void executesTransferReplaysTheKeyAndCompletesViaProviderCallback() throws Exception {
        String key = UUID.randomUUID().toString();
        double completedBefore = eventCount("TransferCompleted");

        ResponseEntity<Map<String, Object>> created = postTransfer(key, "20.00");

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("status", "PROCESSING")
                .containsEntry("fee", 2.0).containsEntry("netAmount", 18.0);
        assertThat(MOCKS.walletAmounts()).containsExactly("-20.00");
        assertThat(MOCKS.paymentBodies()).singleElement().asString().contains("\"amount\":18.00");

        ResponseEntity<Map<String, Object>> replay = postTransfer(key, "20.00");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).containsEntry("id", created.getBody().get("id"));
        assertThat(MOCKS.walletAmounts()).hasSize(1);

        String event = """
                {"reference": "%s", "paymentId": "%s", "status": "COMPLETED"}
                """.formatted(created.getBody().get("id"), FakeOntopMocks.PAYMENT_ID);
        HttpHeaders webhook = new HttpHeaders();
        webhook.setContentType(MediaType.APPLICATION_JSON);
        webhook.set("X-Signature", sign(event));
        assertThat(http.exchange("/internal/v1/payment-events", HttpMethod.POST, new HttpEntity<>(event, webhook),
                Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        URI location = created.getHeaders().getLocation();
        assertThat(http.exchange(location, HttpMethod.GET, new HttpEntity<>(authenticated()), JSON).getBody())
                .containsEntry("status", "COMPLETED");
        assertThat(historyOf(created.getBody().get("id"))).singleElement()
                .satisfies(item -> assertThat(item).containsEntry("displayStatus", "Transaction completed")
                        .containsEntry("amount", -20.0));
        assertThat(eventCount("TransferCompleted")).isEqualTo(completedBefore + 1);
    }

    @Test
    void providerFailureRefundsTheWalletAndShowsBothLinesInHistory() {
        MOCKS.failNextPayment();

        ResponseEntity<Map<String, Object>> response = postTransfer(UUID.randomUUID().toString(), "50.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("status", "REFUNDED");
        assertThat(MOCKS.walletAmounts()).containsExactly("-50.00", "50.00");
        assertThat(historyOf(response.getBody().get("id"))).extracting(item -> item.get("description"))
                .containsExactly("Refund from bank transfer", "Bank transfer to your account");
    }

    @Test
    void providerTimeoutAnswers202AndDoesNotRefund() {
        MOCKS.timeoutNextPayment();

        ResponseEntity<Map<String, Object>> response = postTransfer(UUID.randomUUID().toString(), "15.00");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("status", "IN_REVIEW");
        assertThat(MOCKS.walletAmounts()).containsExactly("-15.00");
    }

    @Test
    void insufficientFundsIsRejectedWithoutTouchingTheWallet() {
        ResponseEntity<Map<String, Object>> response = postTransfer(UUID.randomUUID().toString(), "2500.01");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("code", "insufficient-funds");
        assertThat(MOCKS.walletAmounts()).isEmpty();
    }

    @Test
    void savedRecipientCanReceiveATransferAndBeDeleted() {
        ResponseEntity<Map<String, Object>> recipient = call(HttpMethod.POST, "/v1/recipients", """
                {"firstName": "Ana", "lastName": "Gomez", "nationalId": "AB-1234", "routingNumber": "623852453",
                 "accountNumber": "2300299907", "bankName": "Chase"}
                """, authenticated());
        assertThat(recipient.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(recipient.getBody()).containsEntry("accountLast4", "9907").doesNotContainKey("accountNumber");

        ResponseEntity<Map<String, Object>> quote = call(HttpMethod.POST, "/v1/transfers/quotes",
                "{\"amount\": 20, \"currency\": \"USD\"}", authenticated());
        assertThat(quote.getBody()).containsEntry("fee", 2.0).containsEntry("availableBalance", 2500.0);

        String recipientId = (String) recipient.getBody().get("id");
        assertThat(call(HttpMethod.DELETE, "/v1/recipients/" + recipientId, null, authenticated()).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(call(HttpMethod.DELETE, "/v1/recipients/" + recipientId, null, authenticated()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Minimal stand-in for the Ontop mocks: fixed balance of 2500, wallet transactions always succeed, payments
     * succeed unless told to fail or time out. Records what it receives.
     */
    static final class FakeOntopMocks {

        static final String PAYMENT_ID = "70cfe468-91b9-4e04-8910-5e8257dfadfa";

        private final HttpServer server;
        private final List<String> walletAmounts = new CopyOnWriteArrayList<>();
        private final List<String> paymentBodies = new CopyOnWriteArrayList<>();
        private volatile String nextPaymentError;

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
            nextPaymentError = null;
        }

        void failNextPayment() {
            nextPaymentError = "bank rejected payment";
        }

        void timeoutNextPayment() {
            nextPaymentError = "timeout";
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
            String error = nextPaymentError;
            nextPaymentError = null;
            if (error != null) {
                respond(exchange, 500, "{\"requestInfo\": {\"status\": \"Failed\", \"error\": \"" + error + "\"},"
                        + " \"paymentInfo\": {\"amount\": 45, \"id\": \"f-1\"}}");
                return;
            }
            respond(exchange, 200, "{\"requestInfo\": {\"status\": \"Processing\"},"
                    + " \"paymentInfo\": {\"amount\": 18, \"id\": \"" + PAYMENT_ID + "\"}}");
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
