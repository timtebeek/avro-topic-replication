package com.github.timtebeek.replication;

import java.util.List;
import java.util.concurrent.ExecutionException;

import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.support.Acknowledgment;

import static java.util.stream.Collectors.toList;

@SpringBootApplication
@EnableKafka
public class ReplicationApplication {

	private static final Logger log = LoggerFactory.getLogger(ReplicationApplication.class);

	public static void main(String[] args) throws Exception {
		SpringApplication.run(ReplicationApplication.class, args);
	}

	private final KafkaTemplate<String, GenericRecord> kafkaTemplate;
	private final String targetTopic;

	public ReplicationApplication(
			KafkaTemplate<String, GenericRecord> kafkaTemplate,
			@Value("${replication.target-topic}") String targetTopic) {
		this.kafkaTemplate = kafkaTemplate;
		this.targetTopic = targetTopic;
	}

	@Bean
	public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, GenericRecord>> kafkaManualAckListenerContainerFactory(
			ConsumerFactory<? super String, ? super GenericRecord> consumerFactory) {
		var factory = new ConcurrentKafkaListenerContainerFactory<String, GenericRecord>();
		factory.setBatchListener(true);
		factory.setConsumerFactory(consumerFactory);
		factory.getContainerProperties().setAckMode(AckMode.MANUAL);
		factory.setMissingTopicsFatal(true);
		return factory;
	}

	@KafkaListener(topics = "${replication.source-topic}", containerFactory = "kafkaManualAckListenerContainerFactory")
	public void handle(List<GenericRecord> records, Acknowledgment ack) {
		log.info("Received {} records", records.size());
		// Publish records within a transaction, which can be rolled back on failure
		kafkaTemplate.executeInTransaction(operations -> {
			// Publish each individual record
			var futureSendResults = records.stream()
					.map(record -> {
						var sendResult = operations
								.send(targetTopic, record.get("id").toString(), record);
						// Report on failed records
						sendResult.addCallback(
								success -> log.info("Produced {}", success),
								failure -> log.warn("Failed to produce {}", record, failure));
						return sendResult;
					})
					.collect(toList());
			// Verify each record was published successfully
			for (var future : futureSendResults) {
				try {
					future.get();
				} catch (InterruptedException | ExecutionException e) {
					// When any record fails, throw an exception to prevent transaction commit
					log.warn("Failed to produce a record", e);
					throw new RuntimeException("Failed to produce a record", e);
				}
			}
			// No need for a return value
			return null;
		});
		// Commit offsets for entire batch on consumer
		ack.acknowledge();
		log.info("Replicated {} records", records.size());
	}

}
