package com.ontop.transfers.application.port.out;

import com.ontop.transfers.domain.model.Recipient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecipientRepository {

    Optional<Recipient> findByIdAndUserId(UUID id, long userId);

    List<Recipient> findByUserId(long userId);

    Recipient save(Recipient recipient);

    /**
     * Soft delete: past transfers keep their snapshot. Returns false if the recipient does not exist for the user.
     */
    boolean delete(UUID id, long userId);
}
