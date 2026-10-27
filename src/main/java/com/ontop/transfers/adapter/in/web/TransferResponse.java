package com.ontop.transfers.adapter.in.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ontop.transfers.domain.model.Transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferResponse(
        UUID id,
        String status,
        BigDecimal amount,
        BigDecimal fee,
        BigDecimal netAmount,
        String currency,
        RecipientView recipient,
        String failureReason,
        Instant createdAt) {

    public record RecipientView(String name, String bankName, String accountLast4) {
    }

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.id(),
                transfer.status().name(),
                transfer.amount().amount(),
                transfer.fee().amount(),
                transfer.netAmount().amount(),
                transfer.amount().currency().getCurrencyCode(),
                new RecipientView(transfer.recipient().fullName(), transfer.recipient().bankName(),
                        transfer.recipient().accountLast4()),
                transfer.failureReason(),
                transfer.createdAt());
    }
}
