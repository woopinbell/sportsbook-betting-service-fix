package com.sportsbook.betting.domain;

/**
 * Durable checkpoint of the placement saga. A {@code PENDING} bet advances monotonically through
 * these phases, allowing reconciliation to resume after an ambiguous HTTP response or process crash
 * without guessing which external side effect completed.
 */
public enum PlacementPhase {
  CREATED,
  RISK_RESERVED,
  WALLET_CONFIRMED,
  RISK_COMMITTED
}
