package com.ontop.transfers.adapter.out.messaging;

import com.ontop.transfers.adapter.out.persistence.OutboxStore;
import com.ontop.transfers.adapter.out.persistence.OutboxStore.OutboxMessage;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes outbox rows to Kafka, oldest first, keyed by transfer id so a transfer's events keep their order in one
 * partition. At-least-once: a row is marked published only after the broker acknowledged it, so a crash in between
 * republishes it; consumers deduplicate by {@code eventId}.
 */
@Component
@ConditionalOnProperty(name = "ontop.outbox.relay.enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 100;
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxStore outbox;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final Clock clock;

    public OutboxRelay(OutboxStore outbox, KafkaTemplate<String, String> kafka,
                       @Value("${ontop.outbox.relay.topic}") String topic, Clock clock) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.topic = topic;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ontop.outbox.relay.interval}")
    @SchedulerLock(name = "outbox-relay", lockAtMostFor = "PT1M")
    public void scheduledPublish() {
        publishPending();
    }

    /**
     * @return number of events published in this run
     */
    public int publishPending() {
        List<OutboxMessage> pending = outbox.fetchUnpublished(BATCH_SIZE);
        int published = 0;
        for (OutboxMessage message : pending) {
            if (!send(message)) {
                break;
            }
            outbox.markPublished(message.id(), clock.instant());
            published++;
        }
        return published;
    }

    private boolean send(OutboxMessage message) {
        ProducerRecord<String, String> producerRecord =
                new ProducerRecord<>(topic, message.aggregateId().toString(), message.payload());
        producerRecord.headers().add("eventType", message.type().getBytes());
        try {
            kafka.send(producerRecord).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("Outbox event {} not published, will retry: {}", message.id(), e.getMessage());
            return false;
        }
    }
}
