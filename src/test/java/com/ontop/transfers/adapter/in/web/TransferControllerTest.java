package com.ontop.transfers.adapter.in.web;

import com.ontop.transfers.application.exception.IdempotencyKeyConflictException;
import com.ontop.transfers.application.exception.RecipientNotFoundException;
import com.ontop.transfers.application.exception.TransferInProgressException;
import com.ontop.transfers.application.exception.WalletUnavailableException;
import com.ontop.transfers.application.port.in.ExecuteTransferCommand;
import com.ontop.transfers.application.port.in.ExecuteTransferUseCase;
import com.ontop.transfers.application.port.in.GetTransferUseCase;
import com.ontop.transfers.application.port.in.TransferOutcome;
import com.ontop.transfers.domain.exception.IllegalTransferTransitionException;
import com.ontop.transfers.domain.exception.InsufficientFundsException;
import com.ontop.transfers.domain.model.Transfer;
import com.ontop.transfers.domain.model.TransferStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static com.ontop.transfers.TestData.SEEDED_RECIPIENT_ID;
import static com.ontop.transfers.TestData.USER_ID;
import static com.ontop.transfers.TestData.newTransfer;
import static com.ontop.transfers.TestData.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransferController.class)
class TransferControllerTest {

    private static final String BODY = """
            {"recipientId": "%s", "amount": 20.00, "currency": "USD"}
            """.formatted(SEEDED_RECIPIENT_ID);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ExecuteTransferUseCase executeTransfer;

    @MockitoBean
    private GetTransferUseCase getTransfer;

    private ResultActions postTransfer(String body) throws Exception {
        return mvc.perform(post("/v1/transfers")
                .header("X-User-Id", USER_ID)
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static Transfer processingTransfer() {
        Transfer transfer = newTransfer("20.00");
        transfer.markWalletDebited("59974");
        transfer.markPaymentSubmitted("pay-1");
        return transfer;
    }

    @Test
    void returns201WithLocationAndAmounts() throws Exception {
        Transfer transfer = processingTransfer();
        when(executeTransfer.execute(any())).thenReturn(TransferOutcome.executed(transfer));

        postTransfer(BODY)
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/v1/transfers/" + transfer.id()))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.amount").value(20.00))
                .andExpect(jsonPath("$.fee").value(2.00))
                .andExpect(jsonPath("$.netAmount").value(18.00))
                .andExpect(jsonPath("$.recipient.accountLast4").value("6711"))
                .andExpect(jsonPath("$.failureReason").doesNotExist());

        ArgumentCaptor<ExecuteTransferCommand> command = ArgumentCaptor.forClass(ExecuteTransferCommand.class);
        verify(executeTransfer).execute(command.capture());
        assertThat(command.getValue().userId()).isEqualTo(USER_ID);
        assertThat(command.getValue().amount()).isEqualTo(usd("20.00"));
        assertThat(command.getValue().idempotencyKey()).isEqualTo("key-1");
    }

    @Test
    void returns202WhenOutcomeIsUnknown() throws Exception {
        Transfer transfer = newTransfer("20.00");
        transfer.markWalletDebited("59974");
        transfer.markOutcomeUnknown("Payment outcome unknown: timeout");
        when(executeTransfer.execute(any())).thenReturn(TransferOutcome.executed(transfer));

        postTransfer(BODY)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value(TransferStatus.IN_REVIEW.name()));
    }

    @Test
    void returns200OnIdempotentReplay() throws Exception {
        when(executeTransfer.execute(any())).thenReturn(TransferOutcome.replayed(processingTransfer()));

        postTransfer(BODY).andExpect(status().isOk());
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/v1/transfers")
                        .header("X-User-Id", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(executeTransfer);
    }

    @Test
    void invalidBodyIs400ProblemDetail() throws Exception {
        postTransfer("""
                {"recipientId": null, "amount": -5, "currency": "usd"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", "application/problem+json"));

        verifyNoInteractions(executeTransfer);
    }

    @Test
    void moreThanTwoDecimalsIs400() throws Exception {
        postTransfer("""
                {"recipientId": "%s", "amount": 20.001, "currency": "USD"}
                """.formatted(SEEDED_RECIPIENT_ID))
                .andExpect(status().isBadRequest());
    }

    @Test
    void insufficientFundsIs422() throws Exception {
        when(executeTransfer.execute(any())).thenThrow(new InsufficientFundsException(usd("20"), usd("10")));

        postTransfer(BODY)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("insufficient-funds"));
    }

    @Test
    void unknownRecipientIs404() throws Exception {
        when(executeTransfer.execute(any())).thenThrow(new RecipientNotFoundException(SEEDED_RECIPIENT_ID));

        postTransfer(BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("recipient-not-found"));
    }

    @Test
    void idempotencyConflictIs409() throws Exception {
        when(executeTransfer.execute(any())).thenThrow(new IdempotencyKeyConflictException("key-1"));

        postTransfer(BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency-key-conflict"));
    }

    @Test
    void transferInProgressIs409() throws Exception {
        when(executeTransfer.execute(any())).thenThrow(new TransferInProgressException(USER_ID));

        postTransfer(BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("transfer-in-progress"));
    }

    @Test
    void walletUnavailableIs503WithRetryAfter() throws Exception {
        when(executeTransfer.execute(any())).thenThrow(new WalletUnavailableException("HTTP 500"));

        postTransfer(BODY)
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.code").value("wallet-unavailable"));
    }

    @Test
    void unexpectedErrorIs500WithoutInternals() throws Exception {
        when(executeTransfer.execute(any()))
                .thenThrow(new IllegalTransferTransitionException(UUID.randomUUID(), TransferStatus.CREATED,
                        TransferStatus.COMPLETED));

        postTransfer(BODY)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal-error"))
                .andExpect(jsonPath("$.detail").value("Unexpected error"));
    }

    @Test
    void getReturnsTransfer() throws Exception {
        Transfer transfer = processingTransfer();
        when(getTransfer.get(USER_ID, transfer.id())).thenReturn(transfer);

        mvc.perform(get("/v1/transfers/{id}", transfer.id()).header("X-User-Id", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transfer.id().toString()));
    }
}
