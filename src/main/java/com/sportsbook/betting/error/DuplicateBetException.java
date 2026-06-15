package com.sportsbook.betting.error;

import com.sportsbook.protocol.error.ErrorCode;

/**
 * The same {@code Idempotency-Key} was reused by another actor or with another request payload.
 * Maps to {@link ErrorCode#DUPLICATE_BET} (HTTP 409). A same-actor, same-payload request always
 * replays the durable bet/verdict, including while that bet remains PENDING.
 */
public class DuplicateBetException extends BetPlacementException {

  public DuplicateBetException(String message) {
    super(ErrorCode.DUPLICATE_BET, message);
  }
}
