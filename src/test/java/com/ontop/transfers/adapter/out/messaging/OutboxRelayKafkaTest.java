package com.ontop.transfers.adapter.out.messaging;

import com.ontop.transfers.application.port.out.TransferRepository;
import com.ontop.transfers.domain.model.Transfer;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.ontop.transfers.TestData.newTransfer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outbox to Kafka against an in-memory broker: events written with the transfer reach the topic, keyed by transfer
 * id, and are not published twice.
 */
@SpringBootTest(properties = {
        "ontop.jobs.enabled=false",
        "ontop.outbox.relay.enabled=true",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"})
@EmbeddedKafka(partitions = 1, topics = "transfers.events")
class OutboxRelayKafkaTest {

    @Autowired
    private TransferRepository transfers;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Test
    void publishesTransferEventsInOrderAndOnlyOnce() {
        Transfer transfer = transfers.saveNew(newTransfer(70_001, "20.00", "relay-test"));
        transfer.markWalletDebited("59974");
        transfers.save(transfer);

        relay.publishPending();
        int secondRun = relay.publishPending();

        Map<String, Object> props = KafkaTestUtils.consumerProps("relay-test", "false", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, "transfers.events");
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10)).forEach(received::add);

            List<ConsumerRecord<String, String>> ours = received.stream()
                    .filter(r -> r.key().equals(transfer.id().toString())).toList();
            assertThat(ours).extracting(ConsumerRecord::value)
                    .satisfiesExactly(
                            first -> assertThat(first).contains("\"type\":\"TransferCreated\""),
                            second -> assertThat(second).contains("\"type\":\"WalletDebited\""));
            assertThat(secondRun).isZero();
        }
    }
}
