package com.ontop.transfers.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontop.transfers.adapter.in.web.security.SecurityConfiguration;
import com.ontop.transfers.application.port.in.ApplyPaymentOutcomeUseCase;
import com.ontop.transfers.application.port.in.ApplyPaymentOutcomeUseCase.PaymentNotification;
import com.ontop.transfers.application.port.out.ProviderPaymentStatus;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * Provider callback: final payment status. The body is authenticated with {@code X-Signature: sha256=<hex>}, an
 * HMAC-SHA256 of the raw body with a secret shared with the provider. Answers 204 also for repeated notifications,
 * so the provider stops retrying.
 */
@RestController
public class PaymentEventsController {

    static final String SIGNATURE_HEADER = "X-Signature";
    private static final String SIGNATURE_PREFIX = "sha256=";

    private final ApplyPaymentOutcomeUseCase applyOutcome;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final byte[] secret;

    public PaymentEventsController(ApplyPaymentOutcomeUseCase applyOutcome, ObjectMapper objectMapper,
                                   Validator validator, @Value("${ontop.webhooks.payments-secret}") String secret) {
        this.applyOutcome = applyOutcome;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(SecurityConfiguration.PAYMENT_EVENTS_PATH)
    public ResponseEntity<Void> receive(@RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
                                        @RequestBody String rawBody) {
        if (!validSignature(rawBody, signature)) {
            throw new InvalidSignatureException();
        }
        PaymentEvent event = parse(rawBody);
        applyOutcome.apply(new PaymentNotification(event.reference(), event.paymentId(), event.status(),
                event.reason()));
        return ResponseEntity.noContent().build();
    }

    private PaymentEvent parse(String rawBody) {
        PaymentEvent event;
        try {
            event = objectMapper.readValue(rawBody, PaymentEvent.class);
        } catch (IOException e) {
            throw new InvalidPaymentEventException("Malformed payment event");
        }
        Set<ConstraintViolation<PaymentEvent>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            ConstraintViolation<PaymentEvent> first = violations.iterator().next();
            throw new InvalidPaymentEventException(first.getPropertyPath() + " " + first.getMessage());
        }
        return event;
    }

    private boolean validSignature(String body, String header) {
        if (header == null || !header.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        try {
            byte[] expected = sign(body);
            byte[] received = HexFormat.of().parseHex(header.substring(SIGNATURE_PREFIX.length()));
            return MessageDigest.isEqual(expected, received);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC not available", e);
        }
    }

    /**
     * @param reference the transfer id we sent as Idempotency-Key / reference
     */
    public record PaymentEvent(@NotNull UUID reference, @NotBlank String paymentId,
                               @NotNull ProviderPaymentStatus status, String reason) {
    }

    static class InvalidSignatureException extends RuntimeException {

        InvalidSignatureException() {
            super("Invalid or missing payment event signature");
        }
    }

    static class InvalidPaymentEventException extends RuntimeException {

        InvalidPaymentEventException(String message) {
            super(message);
        }
    }
}
