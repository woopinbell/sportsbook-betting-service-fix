package com.sportsbook.betting.error;

import com.sportsbook.protocol.error.ErrorCode;

/**
 * Base type for placement verdicts and recoverable dependency signals. Each carries a
 * shared-protocol {@link ErrorCode} so the {@code @ControllerAdvice} can render one RFC 7807 shape
 * if it reaches the HTTP boundary.
 *
 * <p>Business subclasses are expected "no" answers and never count as circuit-breaker failures.
 * {@link DependencyUnavailableException} is the explicit infrastructure subclass recorded by the
 * breakers and normally absorbed as a durable PENDING result.
 */
public abstract class BetPlacementException extends RuntimeException {

  private final transient ErrorCode errorCode;

  protected BetPlacementException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public ErrorCode errorCode() {
    return errorCode;
  }
}
