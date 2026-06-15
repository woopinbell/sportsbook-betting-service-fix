package com.sportsbook.betting.client;

import com.sportsbook.betting.error.BetPlacementException;
import com.sportsbook.betting.error.DependencyUnavailableException;
import com.sportsbook.betting.error.DuplicateBetException;
import com.sportsbook.betting.error.RiskLimitException;
import com.sportsbook.protocol.value.Money;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client for the atomic risk reservation lifecycle. A business decline remains a {@link
 * RiskLimitException}; transport and malformed-response failures are ambiguous and become {@link
 * DependencyUnavailableException}, leaving the local bet PENDING for reconciliation.
 *
 * <p>The translation happens in the method body, so it holds with or without the AOP proxy; the
 * {@link CircuitBreaker} adds breaking + the open-circuit fallback on top.
 */
@Component
public class RiskClient {

  private static final String RESERVATIONS_PATH = "/internal/v1/risk/reservations";

  private final RestClient http;

  public RiskClient(@Qualifier("riskRestClient") RestClient http) {
    this.http = http;
  }

  /** Atomically reserves all applicable limits or replays the existing betId reservation. */
  @CircuitBreaker(name = "riskClient", fallbackMethod = "reserveFallback")
  public Reservation reserve(UUID betId, UUID userId, Money stake, List<String> selectionIds) {
    RiskReservationResponse response;
    try {
      response =
          http.post()
              .uri(RESERVATIONS_PATH)
              .contentType(MediaType.APPLICATION_JSON)
              .body(new RiskCheckRequest(userId.toString(), betId.toString(), stake, selectionIds))
              .retrieve()
              .onStatus(
                  status -> status.value() == HttpStatus.CONFLICT.value(),
                  (request, ignored) -> {
                    throw new DuplicateBetException(
                        "Risk reservation conflicts with the original bet payload");
                  })
              .onStatus(
                  HttpStatusCode::is4xxClientError,
                  (request, responseError) -> {
                    throw new DependencyUnavailableException(
                        "unexpected risk reservation response: " + responseError.getStatusCode());
                  })
              .body(RiskReservationResponse.class);
    } catch (BetPlacementException e) {
      throw e;
    } catch (RestClientException e) {
      throw new DependencyUnavailableException(
          "risk-service reservation failed: " + e.getMessage(), e);
    }
    if (response == null) {
      throw new DependencyUnavailableException("risk-service returned an empty reservation body");
    }
    if (!response.approved()) {
      throw new RiskLimitException(response.rejectionReason());
    }
    ReservationState state;
    try {
      state = ReservationState.valueOf(response.reservationState().toUpperCase(Locale.ROOT));
    } catch (RuntimeException invalid) {
      throw new DependencyUnavailableException(
          "risk-service returned an invalid reservation state", invalid);
    }
    return new Reservation(state, response.expiresAt());
  }

  /**
   * Commits a reservation. Returns false only when risk definitively reports it expired/missing.
   */
  @CircuitBreaker(name = "riskClient", fallbackMethod = "commitFallback")
  public boolean commit(UUID betId) {
    try {
      http.put()
          .uri(RESERVATIONS_PATH + "/{betId}/commit", betId)
          .retrieve()
          .onStatus(
              status -> status.value() == HttpStatus.NOT_FOUND.value(),
              (request, response) -> {
                throw new ReservationExpiredMarker();
              })
          .onStatus(
              HttpStatusCode::is4xxClientError,
              (request, response) -> {
                throw new DependencyUnavailableException(
                    "unexpected risk commit response: " + response.getStatusCode());
              })
          .toBodilessEntity();
      return true;
    } catch (ReservationExpiredMarker expired) {
      return false;
    } catch (BetPlacementException e) {
      throw e;
    } catch (RestClientException e) {
      throw new DependencyUnavailableException("risk-service commit failed: " + e.getMessage(), e);
    }
  }

  /**
   * Releases a still-reserved bet. A committed-reservation conflict remains recoverable/PENDING.
   */
  @CircuitBreaker(name = "riskClient", fallbackMethod = "releaseFallback")
  public void release(UUID betId) {
    try {
      http.delete()
          .uri(RESERVATIONS_PATH + "/{betId}", betId)
          .retrieve()
          .onStatus(
              HttpStatusCode::is4xxClientError,
              (request, response) -> {
                throw new DependencyUnavailableException(
                    "risk reservation could not be released: " + response.getStatusCode());
              })
          .toBodilessEntity();
    } catch (BetPlacementException e) {
      throw e;
    } catch (RestClientException e) {
      throw new DependencyUnavailableException("risk-service release failed: " + e.getMessage(), e);
    }
  }

  private Reservation reserveFallback(
      UUID betId, UUID userId, Money stake, List<String> selectionIds, Throwable t) {
    if (t instanceof BetPlacementException placement) {
      throw placement;
    }
    throw new DependencyUnavailableException("risk-service unavailable (circuit open)", t);
  }

  private boolean commitFallback(UUID betId, Throwable t) {
    throw translateFallback(t);
  }

  private void releaseFallback(UUID betId, Throwable t) {
    throw translateFallback(t);
  }

  private static DependencyUnavailableException translateFallback(Throwable t) {
    if (t instanceof DependencyUnavailableException unavailable) {
      return unavailable;
    }
    return new DependencyUnavailableException("risk-service unavailable (circuit open)", t);
  }

  public enum ReservationState {
    RESERVED,
    COMMITTED
  }

  public record Reservation(ReservationState state, Instant expiresAt) {

    public boolean alreadyCommitted() {
      return state == ReservationState.COMMITTED;
    }
  }

  private static final class ReservationExpiredMarker extends RuntimeException {

    private static final long serialVersionUID = 1L;
  }
}
