package dev.seatlock.booking.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;

import dev.seatlock.booking.IntegrationTest;
import dev.seatlock.booking.MutableClock;
import dev.seatlock.booking.event.Event;
import dev.seatlock.booking.event.EventService;
import dev.seatlock.booking.event.Seat;
import dev.seatlock.booking.event.SeatStatus;
import dev.seatlock.booking.hold.Hold;
import dev.seatlock.booking.hold.HoldService;
import dev.seatlock.booking.hold.HoldStatus;
import dev.seatlock.booking.payment.Payment;
import dev.seatlock.booking.payment.PaymentStatus;

@IntegrationTest(properties = { "seatlock.expiry.enabled=false", "seatlock.outbox.enabled=true",
		"seatlock.settlement.enabled=true", "seatlock.settlement.retry-backoff=PT0.01S" })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(120)
class PaymentFlowTest {

	private static final String TOPIC = "payments.requested";

	@LocalServerPort
	int port;

	@Autowired
	EventService events;

	@Autowired
	HoldService holds;

	@Autowired
	FakePaymentProvider provider;

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	KafkaAdmin kafkaAdmin;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MutableClock clock;

	@Test
	void payingOverHttpEndsInAConfirmedBooking() {
		Hold hold = holdOneSeat(2500);

		ResponseEntity<Payment> res = pay(hold);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(res.getBody().status()).isEqualTo(PaymentStatus.PENDING);
		awaitStatus(res.getBody().id(), PaymentStatus.SUCCEEDED);
		assertThat(holds.getHold(hold.id()).status()).isEqualTo(HoldStatus.CONFIRMED);
		assertThat(events.listSeats(hold.eventId())).extracting(Seat::status).containsExactly(SeatStatus.BOOKED);
		assertThat(ledgerCount(res.getBody().id())).isEqualTo(2);
	}

	@Test
	void declinedPaymentEndsWithTheSeatFreeAgain() {
		Hold hold = holdOneSeat(2501);

		Payment failed = awaitStatus(pay(hold).getBody().id(), PaymentStatus.FAILED);

		assertThat(failed.failureReason()).isEqualTo("INSUFFICIENT_FUNDS");
		assertThat(holds.getHold(hold.id()).status()).isEqualTo(HoldStatus.RELEASED);
		assertThat(events.listSeats(hold.eventId())).extracting(Seat::status).containsExactly(SeatStatus.AVAILABLE);
	}

	@Test
	void providerOutageFailsThePaymentAfterRetries() {
		Hold hold = holdOneSeat(2502);

		Payment failed = awaitStatus(pay(hold).getBody().id(), PaymentStatus.FAILED);

		assertThat(failed.failureReason()).isEqualTo("PROVIDER_UNAVAILABLE");
		assertThat(holds.getHold(hold.id()).status()).isEqualTo(HoldStatus.RELEASED);
		assertThat(events.listSeats(hold.eventId())).extracting(Seat::status).containsExactly(SeatStatus.AVAILABLE);
		assertThat(jdbc.sql("select count(*) from ledger_entries").query(Integer.class).single()).isZero();
	}

	@Test
	void aDuplicateMessageChangesNothing() throws Exception {
		UUID first = pay(holdOneSeat(2500)).getBody().id();
		awaitStatus(first, PaymentStatus.SUCCEEDED);

		RecordMetadata duplicate = kafka.send(TOPIC, first.toString(), "{\"paymentId\":\"" + first + "\"}").get()
				.getRecordMetadata();
		awaitStatus(pay(holdOneSeat(2500)).getBody().id(), PaymentStatus.SUCCEEDED);
		// The second payment may sit on another partition, so also wait for the duplicate's offset to be committed.
		awaitCommitted(duplicate);

		assertThat(provider.chargesMade(first)).isEqualTo(1);
		assertThat(ledgerCount(first)).isEqualTo(2);
	}

	@Test
	void badMessagesAreSkipped() throws Exception {
		UUID unknown = UUID.randomUUID();
		// null is a tombstone: a record with no value at all.
		List<String> payloads = Arrays.asList("not json", "null", "{}", "{\"paymentId\":null}",
				"{\"paymentId\":\"not-a-uuid\"}", "", null, "{\"paymentId\":\"" + unknown + "\"}");
		for (String payload : payloads) {
			RecordMetadata sent = kafka.send(TOPIC, UUID.randomUUID().toString(), payload).get().getRecordMetadata();
			// Each record may sit on its own partition, so only its committed offset proves it was not stuck.
			awaitCommitted(sent);
		}

		awaitStatus(pay(holdOneSeat(2500)).getBody().id(), PaymentStatus.SUCCEEDED);
	}

	private Hold holdOneSeat(long priceCents) {
		Event event = events.createEvent("Launch Night", clock.instant().plus(Duration.ofDays(30)), 1, priceCents,
				"MYR");
		List<UUID> seatIds = events.listSeats(event.id()).stream().map(Seat::id).toList();
		return holds.createHold(event.id(), UUID.randomUUID(), seatIds);
	}

	private ResponseEntity<Payment> pay(Hold hold) {
		return client().post().uri("/holds/{id}/pay", hold.id())
				.header("X-User-Id", hold.userId().toString())
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.retrieve().toEntity(Payment.class);
	}

	private Payment awaitStatus(UUID paymentId, PaymentStatus status) {
		return await().atMost(Duration.ofSeconds(15))
				.until(() -> client().get().uri("/payments/{id}", paymentId).retrieve().body(Payment.class),
						p -> p.status() == status);
	}

	private void awaitCommitted(RecordMetadata record) throws Exception {
		var partition = new TopicPartition(record.topic(), record.partition());
		try (var admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
			await().atMost(Duration.ofSeconds(15)).until(() -> {
				OffsetAndMetadata committed = admin.listConsumerGroupOffsets("seatlock-worker")
						.partitionsToOffsetAndMetadata().get().get(partition);
				return committed != null && committed.offset() > record.offset();
			});
		}
	}

	private RestClient client() {
		return RestClient.builder().baseUrl("http://localhost:" + port).build();
	}

	private int ledgerCount(UUID paymentId) {
		return jdbc.sql("select count(*) from ledger_entries where payment_id = ?").param(paymentId)
				.query(Integer.class).single();
	}

}
