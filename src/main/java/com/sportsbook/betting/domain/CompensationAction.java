package com.sportsbook.betting.domain;

/**
 * Irreversible rollback branch selected after a definitive placement decline. Once an action is
 * recorded, the normal risk/debit/accept path is permanently fenced off for that bet.
 */
public enum CompensationAction {
  RISK_RELEASE,
  WALLET_REFUND
}
