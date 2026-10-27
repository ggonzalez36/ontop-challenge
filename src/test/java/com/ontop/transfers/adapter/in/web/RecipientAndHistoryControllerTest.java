package com.ontop.transfers.adapter.in.web;

import com.ontop.transfers.adapter.in.web.security.SecurityConfiguration;
import com.ontop.transfers.application.exception.InvalidCursorException;
import com.ontop.transfers.application.exception.RecipientNotFoundException;
import com.ontop.transfers.application.port.in.GetTransactionHistoryUseCase;
import com.ontop.transfers.application.port.in.GetTransactionHistoryUseCase.HistoryPage;
import com.ontop.transfers.application.port.in.ManageRecipientsUseCase;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository.HistoryEntry;
import com.ontop.transfers.application.port.out.TransactionHistoryRepository.MovementType;
import com.ontop.transfers.domain.exception.InvalidRecipientException;
import com.ontop.transfers.domain.model.TransferStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static com.ontop.transfers.TestData.NOW;
import static com.ontop.transfers.TestData.USER_ID;
import static com.ontop.transfers.TestData.recipient;
import static com.ontop.transfers.TestData.usd;
import static com.ontop.transfers.adapter.in.web.TransferControllerTest.asUser;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({RecipientController.class, TransactionHistoryController.class})
@Import(SecurityConfiguration.class)
class RecipientAndHistoryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ManageRecipientsUseCase recipients;

    @MockitoBean
    private GetTransactionHistoryUseCase history;

    private static final String RECIPIENT = """
            {"firstName": "Lucho", "lastName": "Rodriguez", "nationalId": "3327809",
             "routingNumber": "211927207", "accountNumber": "1885226711", "bankName": "Bank of America"}
            """;

    @Test
    void createsRecipientWithoutExposingSensitiveData() throws Exception {
        when(recipients.create(any())).thenReturn(recipient());

        mvc.perform(asUser(post("/v1/recipients")).contentType(MediaType.APPLICATION_JSON).content(RECIPIENT))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountLast4").value("6711"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.accountNumber").doesNotExist())
                .andExpect(jsonPath("$.nationalId").doesNotExist());
    }

    @Test
    void invalidRecipientIs400() throws Exception {
        when(recipients.create(any())).thenThrow(new InvalidRecipientException("Routing number must have 9 digits"));

        mvc.perform(asUser(post("/v1/recipients")).contentType(MediaType.APPLICATION_JSON).content(RECIPIENT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-recipient"));
    }

    @Test
    void routingNumberFormatIsValidatedAtTheEdge() throws Exception {
        mvc.perform(asUser(post("/v1/recipients")).contentType(MediaType.APPLICATION_JSON)
                        .content(RECIPIENT.replace("211927207", "12")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsRecipients() throws Exception {
        when(recipients.list(USER_ID)).thenReturn(List.of(recipient()));

        mvc.perform(asUser(get("/v1/recipients")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bankName").value("Bank of America"));
    }

    @Test
    void deletingUnknownRecipientIs404() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new RecipientNotFoundException(id)).when(recipients).delete(USER_ID, id);

        mvc.perform(asUser(delete("/v1/recipients/{id}", id))).andExpect(status().isNotFound());
    }

    @Test
    void historyShowsScreenTextsAndCursor() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(history.history(USER_ID, null, 20)).thenReturn(new HistoryPage(List.of(
                new HistoryEntry(2, transferId, MovementType.REFUND, usd("200.00"), TransferStatus.REFUNDED,
                        "Lucho Rodriguez", NOW),
                new HistoryEntry(1, transferId, MovementType.WITHDRAWAL, usd("-200.00"), TransferStatus.REFUNDED,
                        "Lucho Rodriguez", NOW.minusSeconds(5))), "next-cursor"));

        mvc.perform(asUser(get("/v1/transactions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].description").value("Refund from bank transfer"))
                .andExpect(jsonPath("$.items[0].displayStatus").value("Refunded to your wallet"))
                .andExpect(jsonPath("$.items[0].amount").value(200.00))
                .andExpect(jsonPath("$.items[1].description").value("Bank transfer to your account"))
                .andExpect(jsonPath("$.items[1].amount").value(-200.00))
                .andExpect(jsonPath("$.nextCursor").value("next-cursor"));
    }

    @Test
    void invalidCursorIs400() throws Exception {
        when(history.history(USER_ID, "bad", 20)).thenThrow(new InvalidCursorException("bad"));

        mvc.perform(asUser(get("/v1/transactions").param("cursor", "bad")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid-cursor"));
    }
}
