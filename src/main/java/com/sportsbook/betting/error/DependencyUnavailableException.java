package com.sportsbook.betting.error;

import com.sportsbook.protocol.error.ErrorCode;

/**
 * A synchronous dependency (risk or wallet) failed in a non-business way — timeout, connection
 * error, 5xx, an unexpected response, or an open circuit breaker (ADR-0017). This is the
 * recoverable signal: the bet remains PENDING because we could not safely confirm the external
 * operation.
 *
 * <p>Mapped to {@link ErrorCode#SERVICE_UNAVAILABLE} when surfaced outside the placement state
 * machine. This is the only exception the client circuit breakers record.
 */
public class DependencyUnavailableException extends BetPlacementException {

  public DependencyUnavailableException(String message) {
    super(ErrorCode.SERVICE_UNAVAILABLE, message);
  }

  public DependencyUnavailableException(String message, Throwable cause) {
    super(ErrorCode.SERVICE_UNAVAILABLE, message);
    initCause(cause);
  }
}
