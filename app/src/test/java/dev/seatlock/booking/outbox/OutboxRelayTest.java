package dev.seatlock.booking.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.Predicate;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.MutableClock;

@IntegrationTest
@Timeout(120)
class OutboxRelayTest {

	private static final String TOPIC = "payments.requested";

	@Autowired
	OutboxRelay relay;

	@Autowired
	OutboxRepository outbox;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Clock clock;

	@Autowired
	MutableClock mutableClock;

	@Value("${spring.kafka.bootstrap-servers}")
	String bootstrapServers;

	@Test
	void publishesUnpublishedRowsWithKeyAndPayload() {
		String run = UUID.randomUUID().toString();
		for (int i = 0; i < 3; i++) {
			outbox.add(TOPIC, run + "-" + i, "payload-" + i, clock.instant());
		}

		assertThat(relay.publishBatch()).isEqualTo(3);

		List<ConsumerRecord<String, String>> records = readUntil(run, 3);
		assertThat(records).hasSize(3);
		for (int i = 0; i < 3; i++) {
			int n = i;
			assertThat(records).anyMatch(r -> r.key().equals(run + "-" + n) && r.value().equals("payload-" + n));
		}
		assertThat(jdbc.sql("select count(*) from outbox where published_at = ?")
				.param(clock.instant().atOffset(ZoneOffset.UTC)).query(Integer.class).single()).isEqualTo(3);
	}

	@Test
	void publishedRowsAreNotSentAgain() {
		String run = UUID.randomUUID().toString();
		outbox.add(TOPIC, run + "-a", "a", clock.instant());
		outbox.add(TOPIC, run + "-b", "b", clock.instant());

		assertThat(relay.publishBatch()).isEqualTo(2);
		assertThat(relay.publishBatch()).isEqualTo(0);

		assertThat(readUntil(run, 2)).hasSize(2);
	}

	@Test
	void rowsForOneKeyArriveInCreationOrder() {
		String key = UUID.randomUUID().toString();
		for (int i = 0; i < 5; i++) {
			outbox.add(TOPIC, key, "v" + i, clock.instant());
			mutableClock.advance(Duration.ofSeconds(1));
		}

		relay.publishBatch();

		List<ConsumerRecord<String, String>> records = readUntil(key, 5);
		assertThat(records).extracting(ConsumerRecord::value).containsExactly("v0", "v1", "v2", "v3", "v4");
	}

	@Test
	void relaysRunningTogetherPublishEachRowOnce() {
		String run = UUID.randomUUID().toString();
		Instant now = clock.instant();
		for (int i = 0; i < 200; i++) {
			outbox.add(TOPIC, run + "-" + i, "p" + i, now);
		}

		Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int t = 0; t < 4; t++) {
				executor.execute(() -> {
					try {
						start.await();
						while (relay.publishBatch() > 0) {
							// keep going until nothing is left
						}
					}
					catch (Throwable e) {
						failures.add(e);
					}
				});
			}
			start.countDown();
		}

		assertThat(failures).isEmpty();
		List<ConsumerRecord<String, String>> records = readUntil(run, 200);
		assertThat(records).hasSize(200);
		Set<String> keys = new HashSet<>();
		records.forEach(r -> keys.add(r.key()));
		assertThat(keys).hasSize(200);
	}

	/** Reads the shared topic from the start with a fresh group until {@code expected} records for this run's keys arrive, plus a short grace period to catch extras. */
	private List<ConsumerRecord<String, String>> readUntil(String keyPrefix, int expected) {
		Properties props = new Properties();
		props.putAll(Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
				ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString(),
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
				ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
				ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
		Predicate<ConsumerRecord<String, String>> mine = r -> r.key().startsWith(keyPrefix);
		List<ConsumerRecord<String, String>> found = new ArrayList<>();
		try (var consumer = new KafkaConsumer<String, String>(props)) {
			consumer.subscribe(List.of(TOPIC));
			long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
			long graceEnd = 0;
			while (System.nanoTime() < deadline) {
				consumer.poll(Duration.ofMillis(500)).forEach(r -> {
					if (mine.test(r)) {
						found.add(r);
					}
				});
				if (found.size() >= expected) {
					if (graceEnd == 0) {
						graceEnd = System.nanoTime() + Duration.ofSeconds(2).toNanos();
					}
					else if (System.nanoTime() > graceEnd) {
						break;
					}
				}
			}
		}
		return found;
	}

}
