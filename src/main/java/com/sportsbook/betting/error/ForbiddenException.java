package com.sportsbook.betting.error;

/**
 * The gateway actor is missing, malformed, or inconsistent with the requested user. This remains a
 * betting-local error because shared-protocol deliberately has no generic FORBIDDEN catalog entry.
 */
public class ForbiddenException extends RuntimeException {

  public ForbiddenException(String message) {
    super(message);
  }
}
