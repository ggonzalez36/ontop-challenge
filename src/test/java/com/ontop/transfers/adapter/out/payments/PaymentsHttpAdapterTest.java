package com.ontop.transfers.adapter.out.payments;

import com.ontop.transfers.application.port.out.PaymentOrder;
import com.ontop.transfers.application.port.out.PaymentResult;
import com.ontop.transfers.application.port.out.PaymentStatusPort.PaymentLookup;
import com.ontop.transfers.application.port.out.ProviderPaymentStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.UUID;

import static com.ontop.transfers.TestData.recipient;
import static com.ontop.transfers.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PaymentsHttpAdapterTest {

    private static final String URL = "http://payments.test/api/v1/payments";
    private static final PaymentsProperties.SourceAccount ONTOP =
            new PaymentsProperties.SourceAccount("ONTOP INC", "0245253419", "028444018", "USD");

    private MockRestServiceServer server;
    private PaymentsHttpAdapter adapter;
    private CircuitBreaker circuitBreaker;
    private final PaymentOrder order = new PaymentOrder(UUID.randomUUID(), recipient().snapshot(), usd("18.00"));

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://payments.test");
        server = MockRestServiceServer.bindTo(builder).build();
        circuitBreaker = CircuitBreaker.ofDefaults("payments");
        adapter = new PaymentsHttpAdapter(builder.build(), circuitBreaker, ONTOP, true);
    }

    private static String providerResponse(String status, String error) {
        String errorField = error == null ? "" : ", \"error\": \"" + error + "\"";
        return """
                {"requestInfo": {"status": "%s"%s}, "paymentInfo": {"amount": 18, "id": "70cfe468-91b9-4e04-8910-5e8257dfadfa"}}
                """.formatted(status, errorField);
    }

    @Test
    void sendsOntopAccountAsSourceAndNetAmount() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", order.transferId().toString()))
                .andExpect(content().json("""
                        {
                          "source": {
                            "type": "COMPANY",
                            "sourceInformation": {"name": "ONTOP INC"},
                            "account": {"accountNumber": "0245253419", "currency": "USD", "routingNumber": "028444018"}
                          },
                          "destination": {
                            "name": "Lucho Rodriguez",
                            "account": {"accountNumber": "1885226711", "currency": "USD", "routingNumber": "211927207"}
                          },
                          "amount": 18.00
                        }
                        """, true))
                .andRespond(withSuccess(providerResponse("Processing", null), MediaType.APPLICATION_JSON));

        PaymentResult result = adapter.pay(order);

        assertThat(result).isEqualTo(new PaymentResult.Accepted("70cfe468-91b9-4e04-8910-5e8257dfadfa", "Processing"));
        server.verify();
    }

    @Test
    void badRequestIsRejected() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        assertThat(adapter.pay(order)).isInstanceOf(PaymentResult.Rejected.class);
    }

    @Test
    void serverErrorWithExplicitFailureIsRejected() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(providerResponse("Failed", "bank rejected payment")));

        assertThat(adapter.pay(order))
                .isEqualTo(new PaymentResult.Rejected("Provider failed the payment: bank rejected payment"));
    }

    @Test
    void serverErrorReportingTimeoutIsUnknown() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(providerResponse("Failed", "timeout")));

        assertThat(adapter.pay(order)).isInstanceOf(PaymentResult.Unknown.class);
    }

    @Test
    void serverErrorWithoutBodyIsUnknown() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThat(adapter.pay(order)).isInstanceOf(PaymentResult.Unknown.class);
    }

    @Test
    void networkTimeoutIsUnknown() {
        server.expect(requestTo(URL)).andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThat(adapter.pay(order)).isInstanceOf(PaymentResult.Unknown.class);
    }

    @Test
    void openCircuitMeansThePaymentWasNotSent() {
        circuitBreaker.transitionToOpenState();

        assertThat(adapter.pay(order)).isInstanceOf(PaymentResult.Rejected.class);
        server.verify();
    }

    @Test
    void lookupFindsPaymentByReference() {
        server.expect(requestTo(URL + "?reference=" + order.transferId()))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(providerResponse("Completed", null), MediaType.APPLICATION_JSON));

        assertThat(adapter.lookup(order.transferId())).isEqualTo(new PaymentLookup.Found(
                "70cfe468-91b9-4e04-8910-5e8257dfadfa", ProviderPaymentStatus.COMPLETED, null));
    }

    @Test
    void lookupNotFoundMeansThePaymentNeverReachedTheProvider() {
        server.expect(requestTo(URL + "?reference=" + order.transferId())).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(adapter.lookup(order.transferId())).isEqualTo(new PaymentLookup.NotFound());
    }

    @Test
    void lookupServerErrorIsUnavailable() {
        server.expect(requestTo(URL + "?reference=" + order.transferId()))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(adapter.lookup(order.transferId())).isInstanceOf(PaymentLookup.Unavailable.class);
    }

    @Test
    void lookupDisabledNeverCallsTheProvider() {
        PaymentsHttpAdapter withoutLookup = new PaymentsHttpAdapter(RestClient.create(), circuitBreaker, ONTOP, false);

        assertThat(withoutLookup.lookup(order.transferId())).isInstanceOf(PaymentLookup.Unavailable.class);
    }
}
