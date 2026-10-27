package com.ontop.transfers.adapter.out.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontop.transfers.domain.event.TransferEvent;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional outbox: events are written in the same transaction as the transfer change and published later by
 * the relay, so a transfer is never updated without its event and an event never describes a rolled-back change.
 */
@Component
public class OutboxStore {

    private final SpringDataOutboxRepository repository;
    private final ObjectMapper objectMapper;

    OutboxStore(SpringDataOutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    void append(TransferEvent event, Instant occurredAt) {
        UUID eventId = UUID.randomUUID();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("type", event.type());
        envelope.put("transferId", event.transferId());
        envelope.put("occurredAt", occurredAt);
        envelope.put("data", event);
        try {
            repository.save(new OutboxEventEntity(eventId, event.transferId(), event.type(),
                    objectMapper.writeValueAsString(envelope), occurredAt));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + event.type(), e);
        }
    }

    /**
     * Oldest first, so consumers see a transfer's events in order (they are also keyed by transfer id in Kafka).
     */
    public List<OutboxMessage> fetchUnpublished(int limit) {
        return repository.findByPublishedAtIsNullOrderByOccurredAtAsc(Limit.of(limit)).stream()
                .map(e -> new OutboxMessage(e.getId(), e.getAggregateId(), e.getEventType(), e.getPayload()))
                .toList();
    }

    @Transactional
    public void markPublished(UUID eventId, Instant at) {
        repository.findById(eventId).ifPresent(e -> e.markPublished(at));
    }

    public record OutboxMessage(UUID id, UUID aggregateId, String type, String payload) {
    }
}
