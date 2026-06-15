package com.sportsbook.betting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.domain.BetLeg;
import com.sportsbook.betting.error.BetNotFoundException;
import com.sportsbook.betting.error.DuplicateBetException;
import com.sportsbook.betting.error.InsufficientBalanceException;
import com.sportsbook.betting.error.MarketClosedException;
import com.sportsbook.betting.error.OddsDriftException;
import com.sportsbook.betting.error.RiskLimitException;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.betting.placement.BetPlacementService;
import com.sportsbook.betting.placement.BetQueryService;
import com.sportsbook.betting.placement.PlaceBetCommand;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Web-layer + RFC 7807 advice coverage (@WebMvcTest, services mocked). */
@WebMvcTest(BetController.class)
class BetControllerTest {

  private static final UUID USER = UuidV7.generate();
  private static final UUID OTHER_USER = UuidV7.generate();

  @Autowired MockMvc mvc;
  @MockBean BetPlacementService placement;
  @MockBean BetQueryService query;

  private static String singleRequest() {
    return singleRequest(USER);
  }

  private static String singleRequest(UUID userId) {
    return "{"
        + "\"userId\":\""
        + userId
        + "\","
        + "\"slipType\":{\"type\":\"SINGLE\"},"
        + "\"selections\":[{\"eventId\":\""
        + UuidV7.generate()
        + "\",\"marketId\":\""
        + UuidV7.generate()
        + "\",\"selectionId\":\""
        + UuidV7.generate()
        + "\",\"odds\":2.0}],"
        + "\"stake\":{\"amount\":10000,\"currency\":\"KRW\"}"
        + "}";
  }

  private static Bet acceptedBet() {
    Bet bet =
        Bet.pending(
            UuidV7.generate(),
            USER,
            "B-2026-05-29-WEB00001",
            new BetSlipType.Single(),
            Money.krw(10_000),
            Money.krw(20_000),
            IdempotencyKey.of("idem-web"),
            List.of(
                BetLeg.create(
                    UuidV7.generate(),
                    UuidV7.generate(),
                    UuidV7.generate(),
                    Odds.ofDecimal("2.0000"))),
            Instant.parse("2026-05-29T07:00:00Z"));
    bet.recordRiskReservation(Instant.parse("2026-05-29T07:02:00Z"), false, Instant.now());
    bet.confirmWallet(UuidV7.generate(), Instant.now());
    bet.commitRisk(Instant.now());
    bet.accept(Instant.parse("2026-05-29T07:00:01Z"));
    return bet;
  }

  @Test
  @DisplayName("POST accepts -> 201 with Location and ACCEPTED body")
  void placeAccepted() throws Exception {
    when(placement.place(any())).thenReturn(acceptedBet());

    mvc.perform(
            post("/internal/v1/bets")
                .header("X-User-Id", USER)
                .header("Idempotency-Key", "idem-web")
                .contentType(MediaType.APPLICATION_JSON)
                .content(singleRequest()))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/bets/")))
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.betReference").value("B-2026-05-29-WEB00001"))
        .andExpect(jsonPath("$.maxPayout.amount").value(20000));

    ArgumentCaptor<PlaceBetCommand> command = ArgumentCaptor.forClass(PlaceBetCommand.class);
    verify(placement).place(command.capture());
    assertThat(command.getValue().userId()).isEqualTo(USER);
  }

  @Test
  @DisplayName("POST ambiguous dependency result -> 202 with Location and PENDING body")
  void placePending() throws Exception {
    Bet pending =
        Bet.pending(
            UuidV7.generate(),
            USER,
            "B-2026-05-29-WEB00002",
            new BetSlipType.Single(),
            Money.krw(10_000),
            Money.krw(20_000),
            IdempotencyKey.of("idem-pending"),
            List.of(
                BetLeg.create(
                    UuidV7.generate(),
                    UuidV7.generate(),
                    UuidV7.generate(),
                    Odds.ofDecimal("2.0000"))),
            Instant.parse("2026-05-29T07:00:00Z"));
    when(placement.place(any())).thenReturn(pending);

    mvc.perform(
            post("/internal/v1/bets")
                .header("X-User-Id", USER)
                .header("Idempotency-Key", "idem-web")
                .contentType(MediaType.APPLICATION_JSON)
                .content(singleRequest()))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/bets/")))
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  @Test
  @DisplayName("POST without Idempotency-Key -> 400 VALIDATION_FAILED")
  void missingIdempotencyKey() throws Exception {
    mvc.perform(
            post("/internal/v1/bets")
                .header("X-User-Id", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(singleRequest()))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
  }

  @Test
  @DisplayName("POST with empty selections -> 400 (bean validation)")
  void emptySelections() throws Exception {
    String body =
        "{\"userId\":\""
            + USER
            + "\",\"slipType\":{\"type\":\"SINGLE\"},\"selections\":[],"
            + "\"stake\":{\"amount\":10000,\"currency\":\"KRW\"}}";
    mvc.perform(
            post("/internal/v1/bets")
                .header("X-User-Id", USER)
                .header("Idempotency-Key", "idem-web")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
  }

  @Test
  @DisplayName("risk decline -> 403 LIMIT_EXCEEDED")
  void riskDecline() throws Exception {
    when(placement.place(any())).thenThrow(new RiskLimitException("daily limit"));
    expectProblem(403, "LIMIT_EXCEEDED");
  }

  @Test
  @DisplayName("insufficient balance -> 409 INSUFFICIENT_BALANCE")
  void insufficientBalance() throws Exception {
    when(placement.place(any())).thenThrow(new InsufficientBalanceException("too low"));
    expectProblem(409, "INSUFFICIENT_BALANCE");
  }

  @Test
  @DisplayName("odds drift -> 409 ODDS_DRIFT")
  void oddsDrift() throws Exception {
    when(placement.place(any())).thenThrow(new OddsDriftException("drifted"));
    expectProblem(409, "ODDS_DRIFT");
  }

  @Test
  @DisplayName("market closed -> 422 EVENT_CLOSED")
  void marketClosed() throws Exception {
    when(placement.place(any())).thenThrow(new MarketClosedException("suspended"));
    expectProblem(422, "EVENT_CLOSED");
  }

  @Test
  @DisplayName("actor or payload key conflict -> 409 DUPLICATE_BET")
  void duplicate() throws Exception {
    when(placement.place(any())).thenThrow(new DuplicateBetException("different payload"));
    expectProblem(409, "DUPLICATE_BET");
  }

  @Test
  @DisplayName("GET by id not found -> 404 BET_NOT_FOUND")
  void notFound() throws Exception {
    UUID betId = UuidV7.generate();
    when(query.byId(USER, betId)).thenThrow(new BetNotFoundException("nope"));

    mvc.perform(get("/internal/v1/bets/{id}", betId).header("X-User-Id", USER))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errorCode").value("BET_NOT_FOUND"));
  }

  @Test
  @DisplayName("GET by id found -> 200 with body")
  void getById() throws Exception {
    Bet bet = acceptedBet();
    when(query.byId(USER, bet.betId())).thenReturn(bet);

    mvc.perform(get("/internal/v1/bets/{id}", bet.betId()).header("X-User-Id", USER))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACCEPTED"))
        .andExpect(jsonPath("$.selections", org.hamcrest.Matchers.hasSize(1)));
  }

  @Test
  @DisplayName("GET list -> 200 cursor page")
  void listPage() throws Exception {
    when(query.page(any(), any(), any()))
        .thenReturn(new CursorPage<>(List.of(acceptedBet()), "cursor-123", true));

    mvc.perform(get("/internal/v1/bets").header("X-User-Id", USER).param("userId", USER.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items", org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath("$.nextCursor").value("cursor-123"))
        .andExpect(jsonPath("$.hasMore").value(true));
  }

  @Test
  @DisplayName("POST without gateway actor -> 403 betting-local FORBIDDEN")
  void placeMissingActor() throws Exception {
    mvc.perform(
            post("/internal/v1/bets")
                .header("Idempotency-Key", "idem-web")
                .contentType(MediaType.APPLICATION_JSON)
                .content(singleRequest()))
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

    verifyNoInteractions(placement);
  }

  @Test
  @DisplayName("POST with malformed gateway actor -> 403 betting-local FORBIDDEN")
  void placeMalformedActor() throws Exception {
    mvc.perform(
            post("/internal/v1/bets")
                .header("X-User-Id", "not-a-uuid")
                .header("Idempotency-Key", "idem-web")
                .contentType(MediaType.APPLICATION_JSON)
                .content(singleRequest()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

    verifyNoInteractions(placement);
  }

  @Test
  @DisplayName("POST with non-canonical UUID actor -> 403 betting-local FORBIDDEN")
  void placeNonCanonicalActor() throws Exception {
    mvc.perform(
            post("/internal/v1/bets")
                .header("X-User-Id", "1-1-1-1-1")
                .header("Idempotency-Key", "idem-web")
                .contentType(MediaType.APPLICATION_JSON)
                .content(singleRequest()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

    verifyNoInteractions(placement);
  }

  @Test
  @DisplayName("POST body user different from gateway actor -> 403 FORBIDDEN")
  void placeActorMismatch() throws Exception {
    mvc.perform(
            post("/internal/v1/bets")
                .header("X-User-Id", USER)
                .header("Idempotency-Key", "idem-web")
                .contentType(MediaType.APPLICATION_JSON)
                .content(singleRequest(OTHER_USER)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

    verifyNoInteractions(placement);
  }

  @Test
  @DisplayName("GET by id without gateway actor -> 403 FORBIDDEN")
  void getMissingActor() throws Exception {
    mvc.perform(get("/internal/v1/bets/{id}", UuidV7.generate()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

    verifyNoInteractions(query);
  }

  @Test
  @DisplayName("GET by id with malformed gateway actor -> 403 FORBIDDEN")
  void getMalformedActor() throws Exception {
    mvc.perform(get("/internal/v1/bets/{id}", UuidV7.generate()).header("X-User-Id", "not-a-uuid"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

    verifyNoInteractions(query);
  }

  @Test
  @DisplayName("another actor's GET is indistinguishable from an absent bet")
  void getOtherActorsBet() throws Exception {
    UUID betId = UuidV7.generate();
    when(query.byId(OTHER_USER, betId)).thenThrow(new BetNotFoundException("nope"));

    mvc.perform(get("/internal/v1/bets/{id}", betId).header("X-User-Id", OTHER_USER))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.errorCode").value("BET_NOT_FOUND"))
        .andExpect(jsonPath("$.betId").doesNotExist())
        .andExpect(jsonPath("$.userId").doesNotExist());
  }

  @Test
  @DisplayName("GET list without gateway actor -> 403 FORBIDDEN")
  void listMissingActor() throws Exception {
    mvc.perform(get("/internal/v1/bets").param("userId", USER.toString()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

    verifyNoInteractions(query);
  }

  @Test
  @DisplayName("GET list with malformed gateway actor -> 403 FORBIDDEN")
  void listMalformedActor() throws Exception {
    mvc.perform(
            get("/internal/v1/bets")
                .header("X-User-Id", "not-a-uuid")
                .param("userId", USER.toString()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

    verifyNoInteractions(query);
  }

  @Test
  @DisplayName("GET list query user different from gateway actor -> 403 FORBIDDEN")
  void listActorMismatch() throws Exception {
    mvc.perform(
            get("/internal/v1/bets")
                .header("X-User-Id", USER)
                .param("userId", OTHER_USER.toString()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

    verifyNoInteractions(query);
  }

  @Test
  @DisplayName("actor rejection precedes missing idempotency key and malformed JSON")
  void placeMissingActorPrecedesOtherBindingFailures() throws Exception {
    mvc.perform(post("/internal/v1/bets").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

    verifyNoInteractions(placement);
  }

  @Test
  @DisplayName("actor rejection precedes malformed bet id conversion")
  void getMalformedActorPrecedesMalformedBetId() throws Exception {
    mvc.perform(get("/internal/v1/bets/{id}", "not-a-uuid").header("X-User-Id", "also-invalid"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

    verifyNoInteractions(query);
  }

  @Test
  @DisplayName("actor rejection precedes missing user and malformed cursor query")
  void listMissingActorPrecedesOtherBindingFailures() throws Exception {
    mvc.perform(get("/internal/v1/bets").param("cursor", "not-a-uuid"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));

    verifyNoInteractions(query);
  }

  private void expectProblem(int statusCode, String errorCode) throws Exception {
    mvc.perform(
            post("/internal/v1/bets")
                .header("X-User-Id", USER)
                .header("Idempotency-Key", "idem-web")
                .contentType(MediaType.APPLICATION_JSON)
                .content(singleRequest()))
        .andExpect(status().is(statusCode))
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.errorCode").value(errorCode));
  }
}
