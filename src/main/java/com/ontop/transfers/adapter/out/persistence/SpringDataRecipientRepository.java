package com.ontop.transfers.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataRecipientRepository extends JpaRepository<RecipientJpaEntity, UUID> {

    Optional<RecipientJpaEntity> findByIdAndUserIdAndDeletedAtIsNull(UUID id, long userId);

    List<RecipientJpaEntity> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(long userId);
}
