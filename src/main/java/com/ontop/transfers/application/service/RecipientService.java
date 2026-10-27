package com.ontop.transfers.application.service;

import com.ontop.transfers.application.exception.RecipientNotFoundException;
import com.ontop.transfers.application.port.in.ManageRecipientsUseCase;
import com.ontop.transfers.application.port.out.RecipientRepository;
import com.ontop.transfers.domain.exception.InvalidRecipientException;
import com.ontop.transfers.domain.model.Recipient;

import java.util.Currency;
import java.util.List;
import java.util.UUID;

public final class RecipientService implements ManageRecipientsUseCase {

    private final RecipientRepository recipients;

    public RecipientService(RecipientRepository recipients) {
        this.recipients = recipients;
    }

    @Override
    public Recipient create(CreateRecipientCommand command) {
        Recipient recipient = Recipient.create(command.userId(), command.firstName(), command.lastName(),
                command.nationalId(), command.routingNumber(), command.accountNumber(), command.bankName(),
                currency(command.currency()));
        return recipients.save(recipient);
    }

    @Override
    public List<Recipient> list(long userId) {
        return recipients.findByUserId(userId);
    }

    @Override
    public void delete(long userId, UUID recipientId) {
        if (!recipients.delete(recipientId, userId)) {
            throw new RecipientNotFoundException(recipientId);
        }
    }

    private static Currency currency(String code) {
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidRecipientException("Unsupported currency: " + code);
        }
    }
}
