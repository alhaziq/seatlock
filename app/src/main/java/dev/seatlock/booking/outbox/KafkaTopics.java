package dev.seatlock.booking.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration(proxyBeanMethods = false)
class KafkaTopics {

	static final String PAYMENTS_REQUESTED = "payments.requested";

	@Bean
	NewTopic paymentsRequested() {
		return TopicBuilder.name(PAYMENTS_REQUESTED).partitions(3).replicas(1).build();
	}

}
