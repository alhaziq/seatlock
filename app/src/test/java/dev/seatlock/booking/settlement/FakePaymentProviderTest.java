package dev.seatlock.booking.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class FakePaymentProviderTest {

	FakePaymentProvider provider = new FakePaymentProvider(Duration.ZERO);

	@Test
	void approvesOrdinaryAmounts() {
		assertThat(provider.charge(UUID.randomUUID(), 5000, "MYR")).isInstanceOf(ChargeResult.Approved.class);
	}

	@Test
	void declinesAmountsEndingIn01() {
		assertThat(provider.charge(UUID.randomUUID(), 5001, "MYR"))
				.isEqualTo(new ChargeResult.Declined("INSUFFICIENT_FUNDS"));
	}

	@Test
	void timesOutOnAmountsEndingIn02() {
		assertThatThrownBy(() -> provider.charge(UUID.randomUUID(), 5002, "MYR"))
				.isInstanceOf(ProviderTimeoutException.class);
	}

	@Test
	void repeatChargeForOnePaymentIdMovesMoneyOnce() {
		UUID id = UUID.randomUUID();
		ChargeResult first = provider.charge(id, 5000, "MYR");
		ChargeResult second = provider.charge(id, 5000, "MYR");

		assertThat(((ChargeResult.Approved) second).providerReference())
				.isEqualTo(((ChargeResult.Approved) first).providerReference());
		assertThat(provider.chargesMade(id)).isEqualTo(1);
	}

	@Test
	void findChargeReturnsTheRememberedResultAndMovesNoMoney() {
		UUID id = UUID.randomUUID();
		assertThat(provider.findCharge(id)).isEmpty();

		ChargeResult charged = provider.charge(id, 5000, "MYR");

		assertThat(provider.findCharge(id)).contains(charged);
		assertThat(((ChargeResult.Approved) provider.findCharge(id).orElseThrow()).providerReference())
				.isEqualTo(((ChargeResult.Approved) charged).providerReference());
		assertThat(provider.chargesMade(id)).isEqualTo(1);

		UUID timedOut = UUID.randomUUID();
		assertThatThrownBy(() -> provider.charge(timedOut, 5002, "MYR")).isInstanceOf(ProviderTimeoutException.class);
		assertThat(provider.findCharge(timedOut)).isEmpty();
	}

}
