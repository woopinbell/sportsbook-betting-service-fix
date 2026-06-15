package com.sportsbook.betting.placement;

import com.sportsbook.betting.client.RiskClient;
import com.sportsbook.betting.client.RiskClient.Reservation;
import com.sportsbook.betting.client.WalletClient;
import com.sportsbook.betting.client.WalletOperationResponse;
import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.domain.BetLeg;
import com.sportsbook.betting.domain.CompensationAction;
import com.sportsbook.betting.domain.CompensationState;
import com.sportsbook.betting.domain.PlacementPhase;
import com.sportsbook.betting.domain.SystemBetCalculator;
import com.sportsbook.betting.error.BetPlacementException;
import com.sportsbook.betting.error.DependencyUnavailableException;
import com.sportsbook.betting.error.DuplicateBetException;
import com.sportsbook.betting.error.InsufficientBalanceException;
import com.sportsbook.betting.error.MarketClosedException;
import com.sportsbook.betting.error.OddsDriftException;
import com.sportsbook.betting.error.PersistedRejectionException;
import com.sportsbook.betting.error.RiskLimitException;
import com.sportsbook.betting.error.ValidationFailedException;
import com.sportsbook.betting.infrastructure.id.BetReferenceGenerator;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.betting.outbox.BetEventFactory;
import com.sportsbook.betting.outbox.OutboxEvent;
import com.sportsbook.betting.placement.PlaceBetCommand.SelectionInput;
import com.sportsbook.betting.validation.BetSlipValidator;
import com.sportsbook.betting.validation.OddsSlippageChecker;
import com.sportsbook.protocol.domain.BetStatus;
import com.sportsbook.protocol.error.ErrorCode;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Recoverable placement state machine. External calls run between short transactions, while {@link
 * PlacementPhase} checkpoints make every ambiguous response resumable:
 *
 * <pre>
 * CREATED -> RISK_RESERVED -> WALLET_CONFIRMED -> RISK_COMMITTED -> ACCEPTED
 *              |                   |
 *              +-> RISK_RELEASE    +-> WALLET_REFUND
 *                    compensation        compensation
 *                         |                   |
 *                         +-----> REJECTED <--+
 * </pre>
 *
 * <p>Business declines become REJECTED only after any earlier side effect is definitively released
 * or refunded. Compensation intent/progress/proof is persisted separately from the forward phase,
 * so an ambiguous rollback can never resume debit, risk commit, or acceptance. Transport ambiguity
 * always returns the durable PENDING bet.
 */
@Service
public class BetPlacementService {

  private static final Logger log = LoggerFactory.getLogger(BetPlacementService.class);
  private static final int MAX_ADVANCE_STEPS = 8;

  private final BetSlipValidator validator;
  private final OddsSlippageChecker slippageChecker;
  private final SystemBetCalculator calculator;
  private final RiskClient riskClient;
  private final WalletClient walletClient;
  private final BetStore store;
  private final IdempotencyCache idempotency;
  private final BetReferenceGenerator referenceGenerator;
  private final BetEventFactory eventFactory;
  private final Clock clock;

  @SuppressWarnings("checkstyle:ParameterNumber")
  public BetPlacementService(
      BetSlipValidator validator,
      OddsSlippageChecker slippageChecker,
      SystemBetCalculator calculator,
      RiskClient riskClient,
      WalletClient walletClient,
      BetStore store,
      IdempotencyCache idempotency,
      BetReferenceGenerator referenceGenerator,
      BetEventFactory eventFactory,
      Clock clock) {
    this.validator = validator;
    this.slippageChecker = slippageChecker;
    this.calculator = calculator;
    this.riskClient = riskClient;
    this.walletClient = walletClient;
    this.store = store;
    this.idempotency = idempotency;
    this.referenceGenerator = referenceGenerator;
    this.eventFactory = eventFactory;
    this.clock = clock;
  }

  /**
   * Places a new slip or replays its durable result. ACCEPTED is returned synchronously, ambiguous
   * work returns PENDING, and a persisted definitive rejection is rethrown with its original shared
   * error code and detail.
   */
  public Bet place(PlaceBetCommand command) {
    String key = command.idempotencyKey().value();
    String fingerprint = RequestFingerprint.of(command);

    Optional<PlacementRequest> existingRequest = store.findPlacementRequest(key);
    if (existingRequest.isPresent()) {
      return replay(existingRequest.get(), command.userId(), fingerprint);
    }
    // V6 backfills every existing Bet into placement_request. Keep the direct lookup as a
    // conservative bridge for a partially migrated/local test database.
    Optional<Bet> legacyBet = store.findByIdempotencyKey(key);
    if (legacyBet.isPresent()) {
      return replay(legacyBet.get(), command.userId(), fingerprint);
    }

    List<BetLeg> legs;
    Money maxPayout;
    try {
      legs = toLegs(command.selections());
      validator.validate(command.slipType(), legs);
      validator.validateStake(command.unitStake());
      slippageChecker.check(legs);
      maxPayout = calculator.maxPayout(command.slipType(), command.unitStake(), oddsOf(legs));
    } catch (BetPlacementException rejection) {
      if (!isDurablePreflightRejection(rejection)) {
        throw rejection;
      }
      return persistPreflightRejection(command, fingerprint, rejection);
    }

    UUID betId = UuidV7.generate();
    Instant now = clock.instant();
    Bet bet =
        Bet.pending(
            betId,
            command.userId(),
            referenceGenerator.next(now),
            command.slipType(),
            command.unitStake(),
            maxPayout,
            command.idempotencyKey(),
            fingerprint,
            legs,
            now);
    try {
      store.savePending(bet);
    } catch (DataIntegrityViolationException duplicate) {
      return replayKnownOutcome(key, command.userId(), fingerprint, duplicate);
    }
    return advance(betId, false, true);
  }

  /** Resumes one stale PENDING bet without surfacing a business exception to the scheduler. */
  Bet reconcile(UUID betId) {
    return advance(betId, true, false);
  }

  private Bet advance(UUID betId, boolean recovery, boolean surfaceRejection) {
    for (int step = 0; step < MAX_ADVANCE_STEPS; step++) {
      Bet current = store.findById(betId);
      if (current.status() != BetStatus.PENDING) {
        return current;
      }
      try {
        if (current.compensationState() != CompensationState.NONE) {
          switch (current.compensationState()) {
            case REQUIRED -> store.beginCompensation(betId, clock.instant());
            case IN_PROGRESS -> performCompensation(current);
            case COMPLETED -> {
              return finishCompensatedRejection(current, surfaceRejection);
            }
            case NONE -> throw new IllegalStateException("Unreachable compensation state");
          }
          continue;
        }
        switch (current.placementPhase()) {
          case CREATED -> reserveRisk(current, surfaceRejection);
          case RISK_RESERVED -> confirmWallet(current, recovery);
          case WALLET_CONFIRMED -> commitRisk(current);
          case RISK_COMMITTED -> {
            return accept(current);
          }
        }
      } catch (DependencyUnavailableException ambiguous) {
        log.warn(
            "Placement deferred bet {} at {}: {}",
            betId,
            current.placementPhase(),
            ambiguous.getMessage());
        return store.findById(betId);
      } catch (ObjectOptimisticLockingFailureException concurrentAdvance) {
        log.debug("Placement checkpoint concurrently advanced for bet {}", betId);
      }
    }
    log.warn("Placement step bound reached for bet {}; reconciliation will retry", betId);
    return store.findById(betId);
  }

  private void reserveRisk(Bet bet, boolean surfaceRejection) {
    try {
      Reservation reservation =
          riskClient.reserve(bet.betId(), bet.userId(), totalStake(bet), selectionIds(bet.legs()));
      store.recordRiskReservation(
          bet.betId(), reservation.expiresAt(), reservation.alreadyCommitted(), clock.instant());
    } catch (RiskLimitException declined) {
      reject(bet.betId(), declined, surfaceRejection);
    }
  }

  private void confirmWallet(Bet bet, boolean recovery) {
    try {
      UUID operationId = null;
      if (recovery) {
        operationId =
            walletClient
                .findDebit(bet.betId())
                .map(WalletOperationResponse::operationGroupId)
                .orElse(null);
      }
      if (operationId == null) {
        operationId = walletClient.debit(bet.betId(), bet.userId(), totalStake(bet));
      }
      store.confirmWallet(bet.betId(), operationId, clock.instant());
    } catch (InsufficientBalanceException declined) {
      // Save the irreversible release-only branch before the external DELETE. A timeout can then
      // never revisit debit even if the user's balance changes before reconciliation.
      store.requireRiskRelease(
          bet.betId(), declined.errorCode(), declined.getMessage(), clock.instant());
    }
  }

  private void commitRisk(Bet bet) {
    if (bet.riskCommitObserved()) {
      store.commitRisk(bet.betId(), clock.instant());
      return;
    }
    if (riskClient.commit(bet.betId())) {
      store.commitRisk(bet.betId(), clock.instant());
      return;
    }

    // The lease expired after a confirmed debit. Re-reserve under the same betId. If new capacity
    // is unavailable, refund exactly once before persisting the rejection.
    try {
      Reservation replacement =
          riskClient.reserve(bet.betId(), bet.userId(), totalStake(bet), selectionIds(bet.legs()));
      store.recordRiskReservation(
          bet.betId(), replacement.expiresAt(), replacement.alreadyCommitted(), clock.instant());
    } catch (RiskLimitException declined) {
      // Save the irreversible refund-only branch before POSTing the idempotent credit. If the
      // wallet commits and its response is lost, reconciliation may only replay refund:<betId>.
      store.requireWalletRefund(
          bet.betId(), declined.errorCode(), declined.getMessage(), clock.instant());
    }
  }

  private void performCompensation(Bet bet) {
    CompensationAction action = bet.compensationAction();
    if (action == CompensationAction.RISK_RELEASE) {
      riskClient.release(bet.betId());
      store.completeRiskRelease(bet.betId(), clock.instant());
      return;
    }
    if (action == CompensationAction.WALLET_REFUND) {
      UUID operationId = walletClient.refund(bet.betId(), bet.userId(), totalStake(bet));
      store.completeWalletRefund(bet.betId(), operationId, clock.instant());
      return;
    }
    throw new IllegalStateException("PENDING compensation has no action for bet " + bet.betId());
  }

  private Bet finishCompensatedRejection(Bet bet, boolean surface) {
    store.rejectAfterCompensation(bet.betId(), clock.instant());
    Bet rejected = store.findById(bet.betId());
    if (surface) {
      throw persistedRejection(rejected);
    }
    return rejected;
  }

  private Bet accept(Bet bet) {
    OutboxEvent event = eventFactory.placedRequested(bet, clock.instant());
    Bet accepted = store.acceptAndEnqueue(bet.betId(), event, clock.instant());
    idempotency.markProcessed(IdempotencyKey.of(accepted.idempotencyKey()), accepted.betId());
    return accepted;
  }

  private Bet reject(
      UUID betId, com.sportsbook.betting.error.BetPlacementException rejection, boolean surface) {
    store.markRejected(betId, rejection.errorCode(), rejection.getMessage(), clock.instant());
    if (surface) {
      throw rejection;
    }
    return store.findById(betId);
  }

  private static Bet replay(Bet existing, UUID actorId, String fingerprint) {
    if (!existing.userId().equals(actorId)) {
      throw new DuplicateBetException("Idempotency-Key cannot be reused by this actor");
    }
    if (existing.requestFingerprint() != null
        && !existing.requestFingerprint().equals(fingerprint)
        && !existing.requestFingerprint().startsWith("legacy-")) {
      throw new DuplicateBetException(
          "Idempotency-Key cannot be reused with a different request payload");
    }
    if (existing.status() == BetStatus.REJECTED) {
      throw persistedRejection(existing);
    }
    return existing;
  }

  private Bet replay(PlacementRequest request, UUID actorId, String fingerprint) {
    validateReplayIdentity(request.userId(), request.requestFingerprint(), actorId, fingerprint);
    if (request.outcome() == PlacementOutcome.REJECTION) {
      throw persistedRejection(request);
    }
    return replay(store.findById(request.betId()), actorId, fingerprint);
  }

  private Bet replayKnownOutcome(
      String key, UUID actorId, String fingerprint, DataIntegrityViolationException collision) {
    Optional<PlacementRequest> request = store.findPlacementRequest(key);
    if (request.isPresent()) {
      return replay(request.get(), actorId, fingerprint);
    }
    Optional<Bet> legacy = store.findByIdempotencyKey(key);
    if (legacy.isPresent()) {
      return replay(legacy.get(), actorId, fingerprint);
    }
    throw collision;
  }

  private Bet persistPreflightRejection(
      PlaceBetCommand command, String fingerprint, BetPlacementException rejection) {
    try {
      store.savePreflightRejection(
          command.idempotencyKey().value(),
          command.userId(),
          fingerprint,
          rejection.errorCode(),
          rejection.getMessage(),
          clock.instant());
    } catch (DataIntegrityViolationException duplicate) {
      return replayKnownOutcome(
          command.idempotencyKey().value(), command.userId(), fingerprint, duplicate);
    }
    throw rejection;
  }

  private static boolean isDurablePreflightRejection(BetPlacementException rejection) {
    return rejection instanceof ValidationFailedException
        || rejection instanceof MarketClosedException
        || rejection instanceof OddsDriftException;
  }

  private static void validateReplayIdentity(
      UUID savedActor, String savedFingerprint, UUID actorId, String fingerprint) {
    if (!savedActor.equals(actorId)) {
      throw new DuplicateBetException("Idempotency-Key cannot be reused by this actor");
    }
    if (savedFingerprint != null
        && !savedFingerprint.equals(fingerprint)
        && !savedFingerprint.startsWith("legacy-")) {
      throw new DuplicateBetException(
          "Idempotency-Key cannot be reused with a different request payload");
    }
  }

  private static PersistedRejectionException persistedRejection(Bet bet) {
    ErrorCode code;
    try {
      code = ErrorCode.valueOf(bet.rejectionReason());
    } catch (RuntimeException unknownLegacyCode) {
      code = ErrorCode.INTERNAL_ERROR;
    }
    String detail = bet.rejectionDetail() == null ? bet.rejectionReason() : bet.rejectionDetail();
    return new PersistedRejectionException(code, detail);
  }

  private static PersistedRejectionException persistedRejection(PlacementRequest request) {
    ErrorCode code;
    try {
      code = ErrorCode.valueOf(request.errorCode());
    } catch (RuntimeException invalidCode) {
      code = ErrorCode.INTERNAL_ERROR;
    }
    return new PersistedRejectionException(code, request.errorDetail());
  }

  private Money totalStake(Bet bet) {
    return calculator.totalStake(bet.slipType(), bet.stake(), bet.legs().size());
  }

  private static List<BetLeg> toLegs(List<SelectionInput> selections) {
    return selections.stream()
        .map(s -> BetLeg.create(s.eventId(), s.marketId(), s.selectionId(), s.oddsAtSubmission()))
        .toList();
  }

  private static List<Odds> oddsOf(List<BetLeg> legs) {
    return legs.stream().map(BetLeg::oddsAtSubmission).toList();
  }

  private static List<String> selectionIds(List<BetLeg> legs) {
    return legs.stream().map(leg -> leg.selectionId().toString()).toList();
  }
}
