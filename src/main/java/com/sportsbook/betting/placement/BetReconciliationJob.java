package com.sportsbook.betting.placement;

import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.persistence.BetRepository;
import com.sportsbook.protocol.domain.BetStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Resumes stale PENDING placements from their durable checkpoint. Reconciliation first uses the
 * wallet's read-only betId lookup and only reissues the idempotent debit after a definitive 404.
 */
@Component
public class BetReconciliationJob {

  private static final Logger log = LoggerFactory.getLogger(BetReconciliationJob.class);
  static final int BATCH_SIZE = 100;

  private final BetRepository bets;
  private final BetPlacementService placement;
  private final Clock clock;
  private final Duration pendingTimeout;

  public BetReconciliationJob(
      BetRepository bets,
      BetPlacementService placement,
      Clock clock,
      @Value("${betting.reconciliation.pending-timeout:30s}") Duration pendingTimeout) {
    this.bets = bets;
    this.placement = placement;
    this.clock = clock;
    this.pendingTimeout = pendingTimeout;
  }

  @org.springframework.scheduling.annotation.Scheduled(
      fixedDelayString = "${betting.reconciliation.poll-interval-ms:10000}")
  public void reconcile() {
    Instant threshold = clock.instant().minus(pendingTimeout);
    List<Bet> stale =
        bets.findByStatusAndCreatedAtBefore(
            BetStatus.PENDING, threshold, PageRequest.of(0, BATCH_SIZE));
    for (Bet bet : stale) {
      try {
        Bet result = placement.reconcile(bet.betId());
        log.info(
            "Reconciled bet {} from {} to {}/{}",
            bet.betId(),
            bet.placementPhase(),
            result.status(),
            result.placementPhase());
      } catch (RuntimeException unexpected) {
        log.error("Reconciliation failed unexpectedly for bet {}", bet.betId(), unexpected);
      }
    }
  }
}
