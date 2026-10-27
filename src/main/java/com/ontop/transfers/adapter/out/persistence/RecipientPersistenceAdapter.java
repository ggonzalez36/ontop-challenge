package com.ontop.transfers.adapter.out.persistence;

import com.ontop.transfers.application.port.out.RecipientRepository;
import com.ontop.transfers.domain.model.BankAccount;
import com.ontop.transfers.domain.model.Recipient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class RecipientPersistenceAdapter implements RecipientRepository {

    private final SpringDataRecipientRepository repository;
    private final Clock clock;

    RecipientPersistenceAdapter(SpringDataRecipientRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public Optional<Recipient> findByIdAndUserId(UUID id, long userId) {
        return repository.findByIdAndUserIdAndDeletedAtIsNull(id, userId).map(RecipientPersistenceAdapter::toDomain);
    }

    @Override
    public List<Recipient> findByUserId(long userId) {
        return repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId).stream()
                .map(RecipientPersistenceAdapter::toDomain)
                .toList();
    }

    @Override
    public Recipient save(Recipient recipient) {
        BankAccount account = recipient.bankAccount();
        RecipientJpaEntity entity = new RecipientJpaEntity(recipient.id(), recipient.userId(), recipient.firstName(),
                recipient.lastName(), recipient.nationalId(), account.routingNumber(), account.accountNumber(),
                account.bankName(), account.currency().getCurrencyCode(), clock.instant());
        return toDomain(repository.save(entity));
    }

    @Override
    @Transactional
    public boolean delete(UUID id, long userId) {
        Optional<RecipientJpaEntity> entity = repository.findByIdAndUserIdAndDeletedAtIsNull(id, userId);
        entity.ifPresent(e -> e.markDeleted(clock.instant()));
        return entity.isPresent();
    }

    private static Recipient toDomain(RecipientJpaEntity entity) {
        BankAccount account = new BankAccount(entity.getRoutingNumber(), entity.getAccountNumber(),
                entity.getBankName(), Currency.getInstance(entity.getCurrency()));
        return new Recipient(entity.getId(), entity.getUserId(), entity.getFirstName(), entity.getLastName(),
                entity.getNationalId(), account);
    }
}
