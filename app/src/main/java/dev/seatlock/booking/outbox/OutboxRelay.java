package dev.seatlock.booking.outbox;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxRelay {

	private final OutboxRepository outbox;

	private final KafkaTemplate<String, String> kafka;

	private final Clock clock;

	OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka, Clock clock) {
		this.outbox = outbox;
		this.kafka = kafka;
		this.clock = clock;
	}

	/**
	 * Publishes up to 100 unpublished rows, oldest first, and returns how many. A failed send throws and rolls
	 * back, so the rows are retried; after a crash a row may be published twice, so consumers must tolerate
	 * duplicates.
	 */
	@Transactional
	public int publishBatch() {
		List<OutboxRepository.Row> rows = outbox.lockUnpublished(100);
		if (rows.isEmpty()) {
			return 0;
		}
		try {
			var acks = new ArrayList<CompletableFuture<?>>();
			for (var row : rows) {
				acks.add(kafka.send(row.topic(), row.messageKey(), row.payload()));
			}
			for (var ack : acks) {
				ack.get(30, TimeUnit.SECONDS);
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while publishing outbox rows", e);
		}
		catch (ExecutionException | TimeoutException e) {
			throw new IllegalStateException("Kafka did not acknowledge outbox rows", e);
		}
		outbox.markPublished(rows.stream().map(OutboxRepository.Row::id).toList(), clock.instant());
		return rows.size();
	}

}
