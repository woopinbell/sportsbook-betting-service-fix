package com.sportsbook.betting.domain;

/**
 * Durable progress of a placement compensation. REQUIRED records intent before the external call,
 * IN_PROGRESS makes an ambiguous response retry only that idempotent action, and COMPLETED records
 * proof before the bet is finally rejected.
 */
public enum CompensationState {
  NONE,
  REQUIRED,
  IN_PROGRESS,
  COMPLETED
}
