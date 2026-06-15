package com.sportsbook.betting.placement;

import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.outbox.OutboxEvent;
import com.sportsbook.betting.outbox.OutboxEventRepository;
import com.sportsbook.betting.persistence.BetRepository;
import com.sportsbook.betting.persistence.PlacementRequestRepository;
import com.sportsbook.protocol.domain.BetStatus;
import com.sportsbook.protocol.error.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional units of the placement flow, kept in their own bean so {@link
 * BetPlacementService} can call them through the Spring proxy (self-invocation would bypass
 * {@code @Transactional}) and, crucially, so no external HTTP call ever runs inside a DB
 * transaction (ADR-0017 — don't hold a pooled connection across a network round-trip).
 *
 * <p>Each method is one short transaction: claim a placement key, persist PENDING or a preflight
 * rejection, checkpoint compensation, mark REJECTED, or accept + enqueue the outbox row atomically.
 */
@Component
public class BetStore {

  private final BetRepository bets;
  private final OutboxEventRepository outbox;
  private final PlacementRequestRepository requests;

  public BetStore(
      BetRepository bets, OutboxEventRepository outbox, PlacementRequestRepository requests) {
    this.bets = bets;
    this.outbox = outbox;
    this.requests = requests;
  }

  @Transactional(readOnly = true)
  public Optional<PlacementRequest> findPlacementRequest(String idempotencyKey) {
    return requests.findById(idempotencyKey);
  }

  @Transactional(readOnly = true)
  public Optional<Bet> findByIdempotencyKey(String idempotencyKey) {
    return bets.findByIdempotencyKey(idempotencyKey);
  }

  @Transactional(readOnly = true)
  public Bet findById(UUID betId) {
    return bets.findWithLegsByBetId(betId)
        .orElseThrow(() -> new IllegalStateException("Bet not found during placement: " + betId));
  }

  /**
   * Persists the slip as PENDING. {@code saveAndFlush} surfaces a duplicate-key violation here and
   * now (the strong idempotency guard, ADR-0005) rather than at a later flush.
   */
  @Transactional
  public void savePending(Bet bet) {
    bets.saveAndFlush(bet);
    requests.saveAndFlush(PlacementRequest.forBet(bet, bet.createdAt()));
  }

  /** Persists a definitive verdict that occurred before a valid Bet aggregate could be created. */
  @Transactional
  public void savePreflightRejection(
      String idempotencyKey,
      UUID userId,
      String fingerprint,
      ErrorCode errorCode,
      String detail,
      Instant now) {
    requests.saveAndFlush(
        PlacementRequest.rejected(idempotencyKey, userId, fingerprint, errorCode, detail, now));
  }

  /**
   * PENDING -> REJECTED after a risk or wallet decline. Guarded on PENDING so it is idempotent if a
   * reconciliation tick revisits a bet that already settled — no-op if the bet is gone or no longer
   * pending.
   */
  @Transactional
  public void markRejected(UUID betId, ErrorCode reason, String detail, Instant now) {
    bets.findById(betId)
        .filter(bet -> bet.status() == BetStatus.PENDING)
        .ifPresent(bet -> bet.reject(reason.name(), detail, now));
  }

  /** Persists the risk reservation checkpoint, including a replayed COMMITTED observation. */
  @Transactional
  public void recordRiskReservation(
      UUID betId, Instant expiresAt, boolean alreadyCommitted, Instant now) {
    pending(betId).recordRiskReservation(expiresAt, alreadyCommitted, now);
  }

  /** Persists proof of the idempotent wallet debit. */
  @Transactional
  public void confirmWallet(UUID betId, UUID operationId, Instant now) {
    pending(betId).confirmWallet(operationId, now);
  }

  /** Persists the final external placement checkpoint before local acceptance. */
  @Transactional
  public void commitRisk(UUID betId, Instant now) {
    pending(betId).commitRisk(now);
  }

  /** Irreversibly selects risk release after a definitive wallet debit decline. */
  @Transactional
  public void requireRiskRelease(UUID betId, ErrorCode reason, String detail, Instant now) {
    pending(betId).requireRiskRelease(reason.name(), detail, now);
  }

  /** Irreversibly selects wallet refund after risk cannot cover a confirmed debit. */
  @Transactional
  public void requireWalletRefund(UUID betId, ErrorCode reason, String detail, Instant now) {
    pending(betId).requireWalletRefund(reason.name(), detail, now);
  }

  /** Checkpoints compensation progress before its external idempotent request. */
  @Transactional
  public void beginCompensation(UUID betId, Instant now) {
    pending(betId).beginCompensation(now);
  }

  /** Checkpoints a successful risk release response. */
  @Transactional
  public void completeRiskRelease(UUID betId, Instant now) {
    pending(betId).completeRiskRelease(now);
  }

  /** Checkpoints the durable wallet operation returned for a refund. */
  @Transactional
  public void completeWalletRefund(UUID betId, UUID operationId, Instant now) {
    pending(betId).completeWalletRefund(operationId, now);
  }

  /** Converts a fully compensated PENDING bet into its saved definitive rejection. */
  @Transactional
  public void rejectAfterCompensation(UUID betId, Instant now) {
    Bet bet =
        bets.findById(betId)
            .orElseThrow(
                () -> new IllegalStateException("Bet vanished during compensation: " + betId));
    if (bet.status() == BetStatus.REJECTED) {
      return;
    }
    if (bet.status() != BetStatus.PENDING) {
      throw new IllegalStateException(
          "Compensation cannot reject terminal bet " + betId + ": " + bet.status());
    }
    bet.rejectAfterCompensation(now);
  }

  /**
   * PENDING -> ACCEPTED and the BetPlacedRequested outbox row, in one transaction — the atomic step
   * that makes the event inseparable from the acceptance (ADR-0006 / ADR-0017 step 7).
   */
  @Transactional
  public Bet acceptAndEnqueue(UUID betId, OutboxEvent event, Instant now) {
    // Legs eager: the returned bet is rendered into the placement response after the tx closes.
    Bet bet =
        bets.findWithLegsByBetId(betId)
            .orElseThrow(
                () -> new IllegalStateException("Bet vanished before acceptance: " + betId));
    // Idempotent: if a concurrent path / earlier reconciliation tick already accepted this bet,
    // do not accept again or enqueue a duplicate outbox row.
    if (bet.status() != BetStatus.PENDING) {
      return bet;
    }
    bet.accept(now);
    outbox.save(event);
    return bet;
  }

  private Bet pending(UUID betId) {
    Bet bet =
        bets.findById(betId)
            .orElseThrow(
                () -> new IllegalStateException("Bet vanished during placement: " + betId));
    if (bet.status() != BetStatus.PENDING) {
      throw new IllegalStateException(
          "Placement checkpoint cannot update terminal bet " + betId + ": " + bet.status());
    }
    return bet;
  }
}
