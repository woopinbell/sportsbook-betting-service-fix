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
 * 완료된 베팅 접수를 조회하기 위한 Redis 보조 캐시입니다(ADR-0005).
 *
 * <p>요청의 소유권은 PostgreSQL {@code placement_request} 기본 키로만 판단합니다. 첫 데이터베이스 행이 보이기 전에 같은 본문으로 요청한 경우
 * 잘못된 409가 아니라 기존 행이나 {@code PENDING} 결과로 수렴해야 하므로 Redis SETNX는 사용하지 않습니다.
 *
 * <p>키 형식은 {@code idempotency:betting:<caller-key>}이며 유효 시간은 24시간입니다. 값은 운영 확인용으로만 사용하며 처리의 정확성은 이
 * 캐시에 의존하지 않습니다.
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

  /** 운영 확인을 위해 확정된 betId를 기록합니다. 기록 실패는 베팅 접수에 영향을 주지 않습니다. */
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
