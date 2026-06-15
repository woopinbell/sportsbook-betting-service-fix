package com.sportsbook.betting.placement;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.domain.CompensationAction;
import com.sportsbook.betting.domain.CompensationState;
import com.sportsbook.betting.domain.PlacementPhase;
import com.sportsbook.betting.error.DuplicateBetException;
import com.sportsbook.betting.error.MarketClosedException;
import com.sportsbook.betting.error.OddsDriftException;
import com.sportsbook.betting.error.PersistedRejectionException;
import com.sportsbook.betting.error.RiskLimitException;
import com.sportsbook.betting.error.ValidationFailedException;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.betting.outbox.AvroSerializer;
import com.sportsbook.betting.outbox.OutboxEvent;
import com.sportsbook.betting.outbox.OutboxEventRepository;
import com.sportsbook.betting.persistence.BetRepository;
import com.sportsbook.betting.persistence.PlacementRequestRepository;
import com.sportsbook.betting.placement.PlaceBetCommand.SelectionInput;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.domain.BetStatus;
import com.sportsbook.protocol.event.BetPlacedRequested;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end placement orchestration (ADR-0017) against real PostgreSQL + Redis and a WireMock
 * risk/wallet stub. Kafka is excluded — task 6 only writes the outbox row; draining it is task 7.
 */
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    })
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BetPlacementIntegrationTest {

  private static final String RISK_PATH = "/internal/v1/risk/reservations";
  private static final String DEBIT_PATH = "/internal/v1/wallet/transactions/debit";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  static WireMockServer wireMock = new WireMockServer(options().dynamicPort());

  static {
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
    registry.add("spring.data.redis.host", redis::getHost);
    registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    registry.add("betting.clients.risk-base-url", wireMock::baseUrl);
    registry.add("betting.clients.wallet-base-url", wireMock::baseUrl);
  }

  @Autowired BetPlacementService placement;
  @Autowired BetRepository bets;
  @Autowired PlacementRequestRepository placementRequests;
  @Autowired OutboxEventRepository outbox;
  @Autowired StringRedisTemplate redisTemplate;

  private final UUID userId = UuidV7.generate();
  private final UUID eventId = UuidV7.generate();
  private final UUID marketId = UuidV7.generate();
  private final UUID selectionId = UuidV7.generate();

  @BeforeEach
  void setUp() {
    wireMock.resetAll();
    outbox.deleteAll();
    placementRequests.deleteAll();
    bets.deleteAll();
    RedisConnection connection = redisTemplate.getConnectionFactory().getConnection();
    try {
      connection.serverCommands().flushDb();
    } finally {
      connection.close();
    }
    // Seed the odds-feed cache so slippage passes: market OPEN, current odds == submitted.
    redisTemplate.opsForValue().set("market:" + eventId + ":" + marketId, "OPEN");
    redisTemplate
        .opsForValue()
        .set("odds:" + eventId + ":" + marketId + ":" + selectionId, "2.0000");
  }

  private PlaceBetCommand single(String idempotencyKey) {
    return single(userId, idempotencyKey);
  }

  private PlaceBetCommand single(UUID actorId, String idempotencyKey) {
    return single(actorId, idempotencyKey, Money.krw(10_000));
  }

  private PlaceBetCommand single(UUID actorId, String idempotencyKey, Money stake) {
    return new PlaceBetCommand(
        actorId,
        new BetSlipType.Single(),
        List.of(new SelectionInput(eventId, marketId, selectionId, Odds.ofDecimal("2.0000"))),
        stake,
        IdempotencyKey.of(idempotencyKey));
  }

  private void stubRiskApproved() {
    wireMock.stubFor(
        post(urlEqualTo(RISK_PATH))
            .willReturn(
                okJson(
                    "{\"approved\":true,\"reservationState\":\"RESERVED\","
                        + "\"expiresAt\":\"2026-05-29T07:02:00Z\"}")));
    wireMock.stubFor(
        put(urlPathMatching(RISK_PATH + "/[0-9a-f-]+/commit"))
            .willReturn(aResponse().withStatus(204)));
  }

  private void stubWalletDebitOk() {
    wireMock.stubFor(
        post(urlEqualTo(DEBIT_PATH))
            .willReturn(okJson("{\"operationGroupId\":\"" + UuidV7.generate() + "\"}")));
  }

  @RepeatedTest(10)
  @DisplayName(
      "happy path repeated: accepts, computes payout, writes one BetPlacedRequested outbox row")
  void accepts(RepetitionInfo repetition) {
    stubRiskApproved();
    stubWalletDebitOk();

    Bet result = placement.place(single("idem-accept-" + repetition.getCurrentRepetition()));

    assertThat(result.status()).isEqualTo(BetStatus.ACCEPTED);
    assertThat(result.placementPhase()).isEqualTo(PlacementPhase.RISK_COMMITTED);
    assertThat(result.requestFingerprint()).hasSize(64);
    assertThat(result.betReference()).startsWith("B-");
    assertThat(result.maxPayout()).isEqualTo(Money.krw(20_000)); // 10,000 * 2.0

    List<OutboxEvent> events = outbox.findAll();
    assertThat(events).hasSize(1);
    OutboxEvent event = events.get(0);
    assertThat(event.topic()).isEqualTo("bet.placed.v1");
    assertThat(event.schemaName()).isEqualTo("BetPlacedRequested");
    assertThat(event.partitionKey()).isEqualTo(userId.toString());
    BetPlacedRequested decoded =
        AvroSerializer.deserialize(event.payload(), BetPlacedRequested.class);
    assertThat(decoded.getBetId()).hasToString(result.betId().toString());
  }

  @Test
  @DisplayName("risk decline: REJECTED before wallet is ever called, no outbox row")
  void riskDecline() {
    wireMock.stubFor(
        post(urlEqualTo(RISK_PATH))
            .willReturn(okJson("{\"approved\":false,\"rejectionReason\":\"DAILY_LIMIT\"}")));

    assertThatThrownBy(() -> placement.place(single("idem-risk")))
        .isInstanceOf(RiskLimitException.class);

    Bet persisted = bets.findByIdempotencyKey("idem-risk").orElseThrow();
    assertThat(persisted.status()).isEqualTo(BetStatus.REJECTED);
    assertThat(persisted.rejectionReason()).isEqualTo("LIMIT_EXCEEDED");
    wireMock.verify(exactly(0), postRequestedFor(urlEqualTo(DEBIT_PATH)));
    assertThat(outbox.count()).isZero();
  }

  @Test
  @DisplayName("validator rejection owns the key and a changed payload conflicts")
  void validationRejectionIsDurable() {
    PlaceBetCommand invalid = single(userId, "idem-validation", Money.krw(999));

    assertThatThrownBy(() -> placement.place(invalid))
        .isInstanceOf(ValidationFailedException.class)
        .hasMessageContaining("Stake below minimum");
    assertThatThrownBy(() -> placement.place(invalid))
        .isInstanceOf(PersistedRejectionException.class)
        .hasMessageContaining("Stake below minimum");
    assertThatThrownBy(() -> placement.place(single(userId, "idem-validation", Money.krw(1_000))))
        .isInstanceOf(DuplicateBetException.class)
        .hasMessageContaining("different request payload");

    PlacementRequest verdict = placementRequests.findById("idem-validation").orElseThrow();
    assertThat(verdict.outcome()).isEqualTo(PlacementOutcome.REJECTION);
    assertThat(verdict.errorCode()).isEqualTo("VALIDATION_FAILED");
    assertThat(bets.count()).isZero();
    wireMock.verify(exactly(0), postRequestedFor(urlEqualTo(RISK_PATH)));
    wireMock.verify(exactly(0), postRequestedFor(urlEqualTo(DEBIT_PATH)));
  }

  @Test
  @DisplayName("odds drift verdict replays after live odds recover")
  void oddsDriftRejectionSurvivesConditionChange() {
    redisTemplate
        .opsForValue()
        .set("odds:" + eventId + ":" + marketId + ":" + selectionId, "1.5000");

    assertThatThrownBy(() -> placement.place(single("idem-odds-drift")))
        .isInstanceOf(OddsDriftException.class)
        .hasMessageContaining("current 1.5000");

    redisTemplate
        .opsForValue()
        .set("odds:" + eventId + ":" + marketId + ":" + selectionId, "2.0000");

    assertThatThrownBy(() -> placement.place(single("idem-odds-drift")))
        .isInstanceOf(PersistedRejectionException.class)
        .hasMessageContaining("current 1.5000");
    assertThat(placementRequests.findById("idem-odds-drift").orElseThrow().errorCode())
        .isEqualTo("ODDS_DRIFT");
    assertThat(bets.count()).isZero();
    wireMock.verify(exactly(0), postRequestedFor(urlEqualTo(RISK_PATH)));
  }

  @Test
  @DisplayName("market-closed verdict replays after the market opens")
  void marketClosedRejectionSurvivesConditionChange() {
    redisTemplate.opsForValue().set("market:" + eventId + ":" + marketId, "SUSPENDED");

    assertThatThrownBy(() -> placement.place(single("idem-market-closed")))
        .isInstanceOf(MarketClosedException.class)
        .hasMessageContaining("SUSPENDED");

    redisTemplate.opsForValue().set("market:" + eventId + ":" + marketId, "OPEN");

    assertThatThrownBy(() -> placement.place(single("idem-market-closed")))
        .isInstanceOf(PersistedRejectionException.class)
        .hasMessageContaining("SUSPENDED");
    assertThat(placementRequests.findById("idem-market-closed").orElseThrow().errorCode())
        .isEqualTo("EVENT_CLOSED");
    assertThat(bets.count()).isZero();
    wireMock.verify(exactly(0), postRequestedFor(urlEqualTo(RISK_PATH)));
  }

  @Test
  @DisplayName("wallet decline: REJECTED after debit fails, no outbox row")
  void walletDecline() {
    stubRiskApproved();
    wireMock.stubFor(
        delete(urlPathMatching(RISK_PATH + "/[0-9a-f-]+")).willReturn(aResponse().withStatus(204)));
    wireMock.stubFor(
        post(urlEqualTo(DEBIT_PATH))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        "{\"code\":\"WALLET_INSUFFICIENT_BALANCE\",\"detail\":\"too low\"}")));

    assertThatThrownBy(() -> placement.place(single("idem-wallet")))
        .isInstanceOf(PersistedRejectionException.class)
        .hasMessage("too low");

    Bet persisted = bets.findByIdempotencyKey("idem-wallet").orElseThrow();
    assertThat(persisted.status()).isEqualTo(BetStatus.REJECTED);
    assertThat(persisted.rejectionReason()).isEqualTo("INSUFFICIENT_BALANCE");
    assertThat(persisted.rejectionDetail()).isEqualTo("too low");
    wireMock.verify(exactly(1), deleteRequestedFor(urlPathMatching(RISK_PATH + "/[0-9a-f-]+")));
    assertThat(outbox.count()).isZero();
  }

  @Test
  @DisplayName("wallet decline with ambiguous risk release stays PENDING")
  void walletDeclineReleaseAmbiguous() {
    stubRiskApproved();
    wireMock.stubFor(
        delete(urlPathMatching(RISK_PATH + "/[0-9a-f-]+")).willReturn(aResponse().withStatus(503)));
    wireMock.stubFor(
        post(urlEqualTo(DEBIT_PATH))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        "{\"code\":\"WALLET_INSUFFICIENT_BALANCE\",\"detail\":\"too low\"}")));

    Bet result = placement.place(single("idem-release-ambiguous"));

    assertThat(result.status()).isEqualTo(BetStatus.PENDING);
    assertThat(result.placementPhase()).isEqualTo(PlacementPhase.RISK_RESERVED);
    assertThat(result.compensationAction()).isEqualTo(CompensationAction.RISK_RELEASE);
    assertThat(result.compensationState()).isEqualTo(CompensationState.IN_PROGRESS);
    assertThat(result.rejectionReason()).isEqualTo("INSUFFICIENT_BALANCE");
    assertThat(result.rejectionDetail()).isEqualTo("too low");
    assertThat(outbox.count()).isZero();
  }

  @Test
  @DisplayName("same-payload DB race converges to one bet and never returns a false conflict")
  void samePayloadConcurrencyConverges() throws Exception {
    stubRiskApproved();
    stubWalletDebitOk();
    PlaceBetCommand command = single("idem-concurrent");
    int callers = 20;
    CountDownLatch ready = new CountDownLatch(callers);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(callers);
    List<Future<Bet>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < callers; i++) {
        futures.add(
            pool.submit(
                () -> {
                  ready.countDown();
                  start.await();
                  return placement.place(command);
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<Bet> results = new ArrayList<>();
      for (Future<Bet> future : futures) {
        results.add(future.get(20, TimeUnit.SECONDS));
      }

      assertThat(results).extracting(Bet::betId).containsOnly(results.get(0).betId());
      assertThat(results)
          .extracting(Bet::status)
          .allMatch(status -> Set.of(BetStatus.PENDING, BetStatus.ACCEPTED).contains(status));
      assertThat(placement.place(command).status()).isEqualTo(BetStatus.ACCEPTED);
    } finally {
      pool.shutdownNow();
    }

    assertThat(bets.count()).isEqualTo(1);
    assertThat(placementRequests.count()).isEqualTo(1);
    assertThat(outbox.count()).isEqualTo(1);
    wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(RISK_PATH)));
    wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(DEBIT_PATH)));
  }

  @Test
  @DisplayName("idempotent retry: same key -> one accepted bet, one debit, same betId")
  void idempotentRetry() {
    stubRiskApproved();
    stubWalletDebitOk();

    Bet first = placement.place(single("idem-dup"));
    Bet second = placement.place(single("idem-dup"));

    assertThat(second.betId()).isEqualTo(first.betId());
    assertThat(bets.findAll()).hasSize(1);
    assertThat(outbox.count()).isEqualTo(1);
    // The wallet was debited exactly once — the replay short-circuited before any HTTP call.
    wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(DEBIT_PATH)));
  }

  @Test
  @DisplayName("same Idempotency-Key with a changed payload -> 409 before another side effect")
  void idempotencyPayloadConflict() {
    stubRiskApproved();
    stubWalletDebitOk();
    placement.place(single("idem-payload"));
    PlaceBetCommand changed =
        new PlaceBetCommand(
            userId,
            new BetSlipType.Single(),
            List.of(new SelectionInput(eventId, marketId, selectionId, Odds.ofDecimal("2.1000"))),
            Money.krw(10_000),
            IdempotencyKey.of("idem-payload"));

    assertThatThrownBy(() -> placement.place(changed))
        .isInstanceOf(DuplicateBetException.class)
        .hasMessageContaining("different request payload");
    wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(RISK_PATH)));
    wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(DEBIT_PATH)));
  }

  @Test
  @DisplayName("risk transport ambiguity -> durable CREATED/PENDING instead of REJECTED")
  void riskAmbiguityStaysPending() {
    wireMock.stubFor(post(urlEqualTo(RISK_PATH)).willReturn(aResponse().withStatus(503)));

    Bet result = placement.place(single("idem-risk-ambiguous"));

    assertThat(result.status()).isEqualTo(BetStatus.PENDING);
    assertThat(result.placementPhase()).isEqualTo(PlacementPhase.CREATED);
    assertThat(outbox.count()).isZero();
    wireMock.verify(exactly(0), postRequestedFor(urlEqualTo(DEBIT_PATH)));
  }

  @Test
  @DisplayName("wallet transport ambiguity -> durable RISK_RESERVED/PENDING")
  void walletAmbiguityStaysPending() {
    stubRiskApproved();
    wireMock.stubFor(post(urlEqualTo(DEBIT_PATH)).willReturn(aResponse().withStatus(503)));

    Bet result = placement.place(single("idem-wallet-ambiguous"));

    assertThat(result.status()).isEqualTo(BetStatus.PENDING);
    assertThat(result.placementPhase()).isEqualTo(PlacementPhase.RISK_RESERVED);
    assertThat(outbox.count()).isZero();
  }

  @Test
  @DisplayName(
      "definitive rejection replay preserves original code and detail without another call")
  void rejectionReplay() {
    wireMock.stubFor(
        post(urlEqualTo(RISK_PATH))
            .willReturn(okJson("{\"approved\":false,\"rejectionReason\":\"DAILY_STAKE_LIMIT\"}")));

    assertThatThrownBy(() -> placement.place(single("idem-rejected")))
        .isInstanceOf(RiskLimitException.class)
        .hasMessage("DAILY_STAKE_LIMIT");
    assertThatThrownBy(() -> placement.place(single("idem-rejected")))
        .isInstanceOf(PersistedRejectionException.class)
        .hasMessage("DAILY_STAKE_LIMIT");
    wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(RISK_PATH)));
  }

  @Test
  @DisplayName("same Idempotency-Key from another actor -> 409 contract without bet disclosure")
  void idempotencyKeyCannotCrossActors() {
    stubRiskApproved();
    stubWalletDebitOk();
    Bet first = placement.place(single("idem-cross-actor"));

    assertThatThrownBy(() -> placement.place(single(UuidV7.generate(), "idem-cross-actor")))
        .isInstanceOf(DuplicateBetException.class)
        .hasMessage("Idempotency-Key cannot be reused by this actor")
        .hasMessageNotContaining(first.betId().toString())
        .hasMessageNotContaining(first.betReference());

    assertThat(bets.findAll()).hasSize(1);
    assertThat(outbox.count()).isEqualTo(1);
    wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(DEBIT_PATH)));
  }
}
