package com.ontop.transfers.adapter.out.persistence;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SpringDataOutboxRepository extends JpaRepository<OutboxEventEntity, UUID> {

    List<OutboxEventEntity> findByPublishedAtIsNullOrderByOccurredAtAsc(Limit limit);

    List<OutboxEventEntity> findByAggregateIdOrderByOccurredAtAsc(UUID aggregateId);
}
