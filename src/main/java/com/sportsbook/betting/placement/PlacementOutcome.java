package com.sportsbook.betting.placement;

/** Durable Idempotency-Key outcome, shared by normal bet rows and preflight rejections. */
public enum PlacementOutcome {
  BET,
  REJECTION
}
