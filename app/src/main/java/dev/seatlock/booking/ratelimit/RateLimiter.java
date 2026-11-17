package dev.seatlock.booking.ratelimit;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Fixed-window counter per bucket in Redis. shortcut: fixed windows allow up to 2x the limit across a window edge,
 * upgrade to a sliding window or token bucket when that matters. Redis errors are logged and the request proceeds.
 */
@Component
public class RateLimiter {

	private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

	private final StringRedisTemplate redis;

	private final Clock clock;

	private final boolean enabled;

	RateLimiter(StringRedisTemplate redis, Clock clock, @Value("${seatlock.rate-limit.enabled}") boolean enabled) {
		this.redis = redis;
		this.clock = clock;
		this.enabled = enabled;
	}

	public void check(String bucket, int limit, Duration window) {
		if (!enabled) {
			return;
		}
		long windowSeconds = window.toSeconds();
		long now = clock.instant().getEpochSecond();
		long index = now / windowSeconds;
		Long count;
		try {
			String key = "seatlock:rl:" + bucket + ":" + index;
			count = redis.opsForValue().increment(key);
			if (count != null && count == 1) {
				redis.expire(key, window);
			}
		}
		catch (RuntimeException e) {
			log.warn("Rate limiter unavailable, allowing request: {}", e.toString());
			return;
		}
		if (count != null && count > limit) {
			throw new RateLimitExceededException((index + 1) * windowSeconds - now);
		}
	}

}
