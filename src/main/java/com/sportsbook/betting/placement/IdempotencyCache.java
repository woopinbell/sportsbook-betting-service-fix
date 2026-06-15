package com.sportsbook.betting.placement;

import com.sportsbook.protocol.value.IdempotencyKey;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Best-effort Redis correlation cache for completed placement (ADR-0005). PostgreSQL's {@code
 * placement_request} primary key is the sole ownership boundary: a Redis SETNX gate is deliberately
 * not used because a same-payload caller racing before the first DB row becomes visible must
 * converge to that row/PENDING result rather than receive a false 409.
 *
 * <p>Key format {@code idempotency:betting:<caller-key>}, 24h TTL. Values are accepted bet IDs for
 * operational traceability only; correctness never depends on this cache.
 */
@Component
public class IdempotencyCache {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyCache.class);
  static final Duration TTL = Duration.ofHours(24);
  static final String KEY_PREFIX = "idempotency:betting:";

  private final StringRedisTemplate redis;

  public IdempotencyCache(StringRedisTemplate redis) {
    this.redis = redis;
  }

  /** Records the committed betId for diagnostics; failures never affect placement correctness. */
  public void markProcessed(IdempotencyKey key, UUID betId) {
    try {
      redis.opsForValue().set(redisKey(key), betId.toString(), TTL);
    } catch (DataAccessException unavailable) {
      log.warn("Could not update idempotency cache for bet {}", betId);
    }
  }

  private static String redisKey(IdempotencyKey key) {
    return KEY_PREFIX + key.value();
  }
}
