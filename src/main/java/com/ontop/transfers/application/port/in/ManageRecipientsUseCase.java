package com.ontop.transfers.application.port.in;

import com.ontop.transfers.domain.model.Recipient;

import java.util.List;
import java.util.UUID;

public interface ManageRecipientsUseCase {

    Recipient create(CreateRecipientCommand command);

    List<Recipient> list(long userId);

    void delete(long userId, UUID recipientId);

    record CreateRecipientCommand(long userId, String firstName, String lastName, String nationalId,
                                  String routingNumber, String accountNumber, String bankName, String currency) {
    }
}
