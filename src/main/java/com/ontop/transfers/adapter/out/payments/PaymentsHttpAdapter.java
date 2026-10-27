package com.ontop.transfers.adapter.out.payments;

import com.ontop.transfers.application.port.out.PaymentOrder;
import com.ontop.transfers.application.port.out.PaymentProviderPort;
import com.ontop.transfers.application.port.out.PaymentResult;
import com.ontop.transfers.application.port.out.PaymentStatusPort;
import com.ontop.transfers.application.port.out.ProviderPaymentStatus;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Client for the external payments provider. Translates HTTP outcomes into {@link PaymentResult}:
 * <ul>
 *   <li>2xx with a payment id: accepted (provider status is usually "Processing")</li>
 *   <li>4xx, 5xx with an explicit failure, or an open circuit (not sent): rejected, safe to refund</li>
 *   <li>timeout, network error or 5xx reporting a timeout: unknown, must not be refunded automatically</li>
 * </ul>
 */
public class PaymentsHttpAdapter implements PaymentProviderPort, PaymentStatusPort {

    static final String SOURCE_TYPE = "COMPANY";
    private static final String FAILED = "failed";
    private static final String TIMEOUT = "timeout";

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final PaymentsProperties.SourceAccount source;
    private final boolean lookupEnabled;

    public PaymentsHttpAdapter(RestClient restClient, CircuitBreaker circuitBreaker,
                               PaymentsProperties.SourceAccount source, boolean lookupEnabled) {
        this.restClient = restClient;
        this.circuitBreaker = circuitBreaker;
        this.source = source;
        this.lookupEnabled = lookupEnabled;
    }

    @Override
    public PaymentResult pay(PaymentOrder order) {
        try {
            PaymentResponse response = circuitBreaker.executeSupplier(() -> restClient.post()
                    .uri("/api/v1/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Idempotency-Key", order.transferId().toString())
                    .body(toRequest(order))
                    .retrieve()
                    .body(PaymentResponse.class));
            if (response == null || response.paymentInfo() == null || response.paymentInfo().id() == null) {
                return new PaymentResult.Unknown("Provider response without payment id");
            }
            if (FAILED.equals(normalized(response.status()))) {
                return new PaymentResult.Rejected("Provider failed the payment: " + response.error());
            }
            return new PaymentResult.Accepted(response.paymentInfo().id(), response.status());
        } catch (CallNotPermittedException e) {
            return new PaymentResult.Rejected("Payments circuit breaker is open, payment not sent");
        } catch (HttpClientErrorException e) {
            return new PaymentResult.Rejected("Provider rejected the payment: HTTP " + e.getStatusCode().value());
        } catch (HttpServerErrorException e) {
            return fromServerError(e);
        } catch (ResourceAccessException e) {
            return new PaymentResult.Unknown("Provider unreachable: " + e.getMessage());
        }
    }

    /**
     * Assumed contract: {@code GET /api/v1/payments?reference={transferId}} answering like the create endpoint, 404
     * when there is no such payment. Confirm with the provider before enabling.
     */
    @Override
    public PaymentLookup lookup(UUID transferId) {
        if (!lookupEnabled) {
            return new PaymentLookup.Unavailable("Payment lookup is not enabled for this provider");
        }
        try {
            PaymentResponse response = circuitBreaker.executeSupplier(() -> restClient.get()
                    .uri("/api/v1/payments?reference={reference}", transferId)
                    .retrieve()
                    .body(PaymentResponse.class));
            if (response == null || response.paymentInfo() == null || response.paymentInfo().id() == null) {
                return new PaymentLookup.Unavailable("Provider lookup without payment id");
            }
            return statusOf(response.status())
                    .<PaymentLookup>map(status -> new PaymentLookup.Found(response.paymentInfo().id(), status,
                            response.error()))
                    .orElseGet(() -> new PaymentLookup.Unavailable("Unknown provider status " + response.status()));
        } catch (HttpClientErrorException.NotFound e) {
            return new PaymentLookup.NotFound();
        } catch (CallNotPermittedException e) {
            return new PaymentLookup.Unavailable("Payments circuit breaker is open");
        } catch (RestClientException e) {
            return new PaymentLookup.Unavailable("Provider lookup failed: " + e.getMessage());
        }
    }

    private static Optional<ProviderPaymentStatus> statusOf(String providerStatus) {
        return switch (normalized(providerStatus)) {
            case "processing" -> Optional.of(ProviderPaymentStatus.PROCESSING);
            case "completed" -> Optional.of(ProviderPaymentStatus.COMPLETED);
            case FAILED -> Optional.of(ProviderPaymentStatus.FAILED);
            default -> Optional.empty();
        };
    }

    private static PaymentResult fromServerError(HttpServerErrorException e) {
        PaymentResponse body = parse(e);
        String error = body == null ? null : body.error();
        if (body == null || error == null || normalized(error).contains(TIMEOUT)) {
            return new PaymentResult.Unknown("Provider HTTP " + e.getStatusCode().value() + ": " + error);
        }
        if (FAILED.equals(normalized(body.status()))) {
            return new PaymentResult.Rejected("Provider failed the payment: " + error);
        }
        return new PaymentResult.Unknown("Provider HTTP " + e.getStatusCode().value() + ": " + error);
    }

    private static PaymentResponse parse(HttpServerErrorException e) {
        try {
            return e.getResponseBodyAs(PaymentResponse.class);
        } catch (RuntimeException parseFailure) {
            return null;
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private PaymentRequest toRequest(PaymentOrder order) {
        Account sourceAccount = new Account(source.accountNumber(), source.currency(), source.routingNumber());
        Account destinationAccount = new Account(order.beneficiary().accountNumber(),
                order.amount().currency().getCurrencyCode(), order.beneficiary().routingNumber());
        return new PaymentRequest(
                new Source(SOURCE_TYPE, new SourceInformation(source.name()), sourceAccount),
                new Destination(order.beneficiary().fullName(), destinationAccount),
                order.amount().amount());
    }

    record PaymentRequest(Source source, Destination destination, BigDecimal amount) {
    }

    record Source(String type, SourceInformation sourceInformation, Account account) {
    }

    record SourceInformation(String name) {
    }

    record Destination(String name, Account account) {
    }

    record Account(String accountNumber, String currency, String routingNumber) {
    }

    record PaymentResponse(RequestInfo requestInfo, PaymentInfo paymentInfo) {

        String status() {
            return requestInfo == null ? null : requestInfo.status();
        }

        String error() {
            return requestInfo == null ? null : requestInfo.error();
        }
    }

    record RequestInfo(String status, String error) {
    }

    record PaymentInfo(BigDecimal amount, String id) {
    }
}
