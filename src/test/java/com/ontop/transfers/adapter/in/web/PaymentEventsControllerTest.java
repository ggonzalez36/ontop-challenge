package com.ontop.transfers.adapter.in.web;

import com.ontop.transfers.adapter.in.web.security.SecurityConfiguration;
import com.ontop.transfers.application.exception.PaymentOutcomeConflictException;
import com.ontop.transfers.application.port.in.ApplyPaymentOutcomeUseCase;
import com.ontop.transfers.application.port.in.ApplyPaymentOutcomeUseCase.PaymentNotification;
import com.ontop.transfers.application.port.out.ProviderPaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PaymentEventsController.class)
@Import(SecurityConfiguration.class)
class PaymentEventsControllerTest {

    /** Same as the dev default in application.yml. */
    static final String SECRET = "dev-only-payments-webhook-secret";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ApplyPaymentOutcomeUseCase applyOutcome;

    private final UUID transferId = UUID.randomUUID();
    private final String body = """
            {"reference": "%s", "paymentId": "pay-1", "status": "COMPLETED"}
            """.formatted(transferId);

    static String signature(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private ResultActions send(String payload, String signature) throws Exception {
        var request = post("/internal/v1/payment-events").contentType(MediaType.APPLICATION_JSON).content(payload);
        if (signature != null) {
            request.header("X-Signature", signature);
        }
        return mvc.perform(request);
    }

    @Test
    void validSignatureAppliesTheOutcomeWithoutAJwt() throws Exception {
        send(body, signature(body)).andExpect(status().isNoContent());

        verify(applyOutcome).apply(new PaymentNotification(transferId, "pay-1", ProviderPaymentStatus.COMPLETED, null));
    }

    @Test
    void missingSignatureIs401() throws Exception {
        send(body, null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid-signature"));

        verifyNoInteractions(applyOutcome);
    }

    @Test
    void tamperedBodyIs401() throws Exception {
        String signature = signature(body);

        send(body.replace("COMPLETED", "FAILED"), signature).andExpect(status().isUnauthorized());

        verifyNoInteractions(applyOutcome);
    }

    @Test
    void signedButIncompleteEventIs400() throws Exception {
        String incomplete = "{\"paymentId\": \"pay-1\", \"status\": \"COMPLETED\"}";

        send(incomplete, signature(incomplete))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-payment-event"));
    }

    @Test
    void contradictoryOutcomeIs409() throws Exception {
        when(applyOutcome.apply(any())).thenThrow(new PaymentOutcomeConflictException(transferId, "already refunded"));

        send(body, signature(body)).andExpect(status().isConflict());
    }
}
