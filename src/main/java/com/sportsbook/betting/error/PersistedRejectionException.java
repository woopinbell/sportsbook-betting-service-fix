package com.sportsbook.betting.error;

import com.sportsbook.protocol.error.ErrorCode;

/**
 * Replays the exact shared error code and detail persisted for a definitive placement rejection.
 */
public class PersistedRejectionException extends BetPlacementException {

  public PersistedRejectionException(ErrorCode errorCode, String detail) {
    super(errorCode, detail);
  }
}
