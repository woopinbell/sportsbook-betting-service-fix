package com.sportsbook.betting.placement;

import com.sportsbook.betting.domain.Bet;
import com.sportsbook.protocol.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Authoritative Idempotency-Key namespace for placement. A key resolves either to a bet row or to a
 * definitive rejection that happened before a valid {@link Bet} aggregate could be created.
 */
@Entity
@Table(name = "placement_request")
public class PlacementRequest {

  @Id
  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
  private String idempotencyKey;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "request_fingerprint", updatable = false, length = 64)
  private String requestFingerprint;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", nullable = false, updatable = false, length = 16)
  private PlacementOutcome outcome;

  @Column(name = "bet_id", updatable = false)
  private UUID betId;

  @Column(name = "error_code", updatable = false, length = 64)
  private String errorCode;

  @Column(name = "error_detail", updatable = false, length = 1024)
  private String errorDetail;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PlacementRequest() {
    // Required by JPA.
  }

  private PlacementRequest(
      String idempotencyKey,
      UUID userId,
      String requestFingerprint,
      PlacementOutcome outcome,
      UUID betId,
      String errorCode,
      String errorDetail,
      Instant createdAt) {
    this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
    this.userId = Objects.requireNonNull(userId, "userId");
    this.requestFingerprint = Objects.requireNonNull(requestFingerprint, "requestFingerprint");
    this.outcome = Objects.requireNonNull(outcome, "outcome");
    this.betId = betId;
    this.errorCode = errorCode;
    this.errorDetail = errorDetail;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
  }

  public static PlacementRequest forBet(Bet bet, Instant createdAt) {
    Objects.requireNonNull(bet, "bet");
    return new PlacementRequest(
        bet.idempotencyKey(),
        bet.userId(),
        bet.requestFingerprint(),
        PlacementOutcome.BET,
        bet.betId(),
        null,
        null,
        createdAt);
  }

  public static PlacementRequest rejected(
      String idempotencyKey,
      UUID userId,
      String requestFingerprint,
      ErrorCode errorCode,
      String errorDetail,
      Instant createdAt) {
    Objects.requireNonNull(errorCode, "errorCode");
    return new PlacementRequest(
        idempotencyKey,
        userId,
        requestFingerprint,
        PlacementOutcome.REJECTION,
        null,
        errorCode.name(),
        requireText(errorDetail, "errorDetail"),
        createdAt);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }

  public String idempotencyKey() {
    return idempotencyKey;
  }

  public UUID userId() {
    return userId;
  }

  public String requestFingerprint() {
    return requestFingerprint;
  }

  public PlacementOutcome outcome() {
    return outcome;
  }

  public UUID betId() {
    return betId;
  }

  public String errorCode() {
    return errorCode;
  }

  public String errorDetail() {
    return errorDetail;
  }

  public Instant createdAt() {
    return createdAt;
  }
}
