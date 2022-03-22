package com.github.timtebeek.replication;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

import io.confluent.kafka.schemaregistry.testutil.MockSchemaRegistry;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.GenericRecordBuilder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import static com.github.timtebeek.replication.ReplicationApplicationTest.INPUT_TOPIC;
import static com.github.timtebeek.replication.ReplicationApplicationTest.OUTPUT_TOPIC;
import static io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.kafka.test.assertj.KafkaConditions.key;
import static org.springframework.kafka.test.assertj.KafkaConditions.value;

@SpringBootTest
@EmbeddedKafka(topics = { INPUT_TOPIC, OUTPUT_TOPIC }, brokerProperties = {
		// Allow transactions to work even with a single embedded broker
		"transaction.state.log.replication.factor=1",
		"transaction.state.log.min.isr=1"
})
class ReplicationApplicationTest {

	private static final Logger log = LoggerFactory.getLogger(ReplicationApplicationTest.class);

	static final String INPUT_TOPIC = "input-topic";
	static final String OUTPUT_TOPIC = "output-topic";

	private final String SOURCE_REGISTRY_SCOPE = "SourceRegistry";
	private final String TARGET_REGISTRY_SCOPE = "TargetRegistry";

	@Autowired
	private EmbeddedKafkaBroker broker;

	private KafkaProducer<String, GenericRecord> inputProducer;
	private KafkaConsumer<String, GenericRecord> outputConsumer;

	@BeforeEach
	void beforeEach() {
		Map<String, Object> producerprops = KafkaTestUtils.producerProps(broker);
		producerprops.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
		producerprops.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
		producerprops.put(SCHEMA_REGISTRY_URL_CONFIG, "mock://" + SOURCE_REGISTRY_SCOPE);
		inputProducer = new KafkaProducer<>(producerprops);

		Map<String, Object> consumerprops = KafkaTestUtils.consumerProps("test-consumer", "true", broker);
		consumerprops.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
		consumerprops.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
		consumerprops.put(SCHEMA_REGISTRY_URL_CONFIG, "mock://" + TARGET_REGISTRY_SCOPE);
		consumerprops.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
		outputConsumer = new KafkaConsumer<>(consumerprops);
		outputConsumer.subscribe(List.of(OUTPUT_TOPIC));
	}

	@AfterEach
	void afterEach() {
		MockSchemaRegistry.dropScope(SOURCE_REGISTRY_SCOPE);
		MockSchemaRegistry.dropScope(TARGET_REGISTRY_SCOPE);
	}

	@Test
	void testReplication() throws Exception {
		// Define a generic Avro schema
		String idField = "id";
		Schema schema = SchemaBuilder.builder().record("com.foo.Foo").fields()
				.name(idField).type("string").noDefault()
				.endRecord();

		// Send in a keyless generic record
		String id = UUID.randomUUID().toString();
		GenericRecord record = new GenericRecordBuilder(schema).set(idField, id).build();
		Future<RecordMetadata> send = inputProducer
				.send(new ProducerRecord<String, GenericRecord>(INPUT_TOPIC, record));
		RecordMetadata recordMetadata = send.get();
		log.info("Produced {}", recordMetadata);

		// Verify consumer offset commit
		Awaitility.await("consumer offset commit").untilAsserted(() -> {
			OffsetAndMetadata offsetAndMetadata = KafkaTestUtils.getCurrentOffset(
					broker.getBrokersAsString(),
					"replicator",
					INPUT_TOPIC,
					0);
			log.info("Received {}", offsetAndMetadata);
			assertThat(offsetAndMetadata)
					.isNotNull()
					.extracting(OffsetAndMetadata::offset)
					.isEqualTo(1L);
		});

		// Verify replicated to output topic
		Awaitility.await().untilAsserted(() -> {
			ConsumerRecord<String, GenericRecord> consumerRecord = KafkaTestUtils.getSingleRecord(outputConsumer,
					OUTPUT_TOPIC);
			log.info("Received {}", consumerRecord);
			assertThat(consumerRecord).has(key(id));
			assertThat(consumerRecord).has(value(record));
		});
	}

}
