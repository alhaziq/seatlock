package dev.seatlock.booking.event;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Short-lived Redis copy of an event's seat list. Redis errors are logged and the loader result is used as is. */
@Component
class SeatCache {

	private static final Logger log = LoggerFactory.getLogger(SeatCache.class);

	private static final TypeReference<List<Seat>> SEATS = new TypeReference<>() { };

	private final StringRedisTemplate redis;

	private final JsonMapper json;

	private final boolean enabled;

	private final Duration ttl;

	SeatCache(StringRedisTemplate redis, JsonMapper json, @Value("${seatlock.seat-cache.enabled}") boolean enabled,
			@Value("${seatlock.seat-cache.ttl}") Duration ttl) {
		this.redis = redis;
		this.json = json;
		this.enabled = enabled;
		this.ttl = ttl;
	}

	List<Seat> get(UUID eventId, Supplier<List<Seat>> load) {
		if (!enabled) {
			return load.get();
		}
		String key = "seatlock:seats:" + eventId;
		try {
			String cached = redis.opsForValue().get(key);
			if (cached != null) {
				return json.readValue(cached, SEATS);
			}
		}
		catch (RuntimeException e) {
			log.warn("Seat cache read failed, loading from the database: {}", e.toString());
		}
		List<Seat> seats = load.get();
		try {
			redis.opsForValue().set(key, json.writeValueAsString(seats), ttl);
		}
		catch (RuntimeException e) {
			log.warn("Seat cache write failed: {}", e.toString());
		}
		return seats;
	}

}
