package com.sportsbook.betting.error;

import com.sportsbook.protocol.error.ErrorCode;

/**
 * risk-service declined the atomic reservation on a limit or pattern rule. The reservation endpoint
 * returns this as HTTP 200 with {@code approved:false}, so it is a business rejection — never an
 * infrastructure failure or Resilience4j circuit-breaker trip. Maps to {@link
 * ErrorCode#LIMIT_EXCEEDED} (HTTP 403).
 */
public class RiskLimitException extends BetPlacementException {

  public RiskLimitException(String message) {
    super(ErrorCode.LIMIT_EXCEEDED, message == null ? "Risk limit exceeded" : message);
  }
}
