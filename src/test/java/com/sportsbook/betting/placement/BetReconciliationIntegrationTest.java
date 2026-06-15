package com.sportsbook.betting.placement;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.domain.BetLeg;
import com.sportsbook.betting.domain.CompensationAction;
import com.sportsbook.betting.domain.CompensationState;
import com.sportsbook.betting.domain.PlacementPhase;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.betting.outbox.OutboxEventRepository;
import com.sportsbook.betting.persistence.BetRepository;
import com.sportsbook.betting.persistence.PlacementRequestRepository;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.domain.BetStatus;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Money;
import com.sportsbook.protocol.value.Odds;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Recovery coverage for every ambiguous placement checkpoint. */
@SpringBootTest(
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
    })
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BetReconciliationIntegrationTest {

  private static final String RISK_PATH = "/internal/v1/risk/reservations";
  private static final String DEBIT_PATH = "/internal/v1/wallet/transactions/debit";
  private static final String CREDIT_PATH = "/internal/v1/wallet/transactions/credit";

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  private static final DurableRefundTransformer DURABLE_REFUND = new DurableRefundTransformer();

  static WireMockServer wireMock =
      new WireMockServer(options().dynamicPort().extensions(DURABLE_REFUND));

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
    registry.add("betting.clients.wallet-base-url", wireMock::baseUrl);
    registry.add("betting.clients.risk-base-url", wireMock::baseUrl);
  }

  @Autowired BetReconciliationJob reconciliation;
  @Autowired BetStore store;
  @Autowired BetRepository bets;
  @Autowired PlacementRequestRepository placementRequests;
  @Autowired OutboxEventRepository outbox;

  private final UUID userId = UuidV7.generate();

  @BeforeEach
  void setUp() {
    wireMock.resetAll();
    DURABLE_REFUND.reset();
    outbox.deleteAll();
    placementRequests.deleteAll();
    bets.deleteAll();
  }

  private UUID stalePending(String reference, String idempotencyKey) {
    UUID betId = UuidV7.generate();
    Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
    Bet bet =
        Bet.pending(
            betId,
            userId,
            reference,
            new BetSlipType.Single(),
            Money.krw(10_000),
            Money.krw(20_000),
            IdempotencyKey.of(idempotencyKey),
            "a".repeat(64),
            List.of(
                BetLeg.create(
                    UuidV7.generate(),
                    UuidV7.generate(),
                    UuidV7.generate(),
                    Odds.ofDecimal("2.0000"))),
            past);
    store.savePending(bet);
    return betId;
  }

  private void stageRiskReserved(UUID betId) {
    store.recordRiskReservation(
        betId, Instant.now().plus(2, ChronoUnit.MINUTES), false, Instant.now());
  }

  private void stageWalletConfirmed(UUID betId) {
    stageRiskReserved(betId);
    store.confirmWallet(betId, UuidV7.generate(), Instant.now());
  }

  private void stubRiskReservedAndCommit() {
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

  @Test
  @DisplayName("CREATED recovery reserves risk, confirms missing lookup by debit, and accepts")
  void recoverCreated() {
    stubRiskReservedAndCommit();
    stubWalletDebitOk();
    UUID betId = stalePending("B-2026-05-29-RECON-CR", "recon-created");
    wireMock.stubFor(
        get(urlEqualTo(DEBIT_PATH + "/" + betId)).willReturn(aResponse().withStatus(404)));

    reconciliation.reconcile();

    Bet result = bets.findById(betId).orElseThrow();
    assertThat(result.status()).isEqualTo(BetStatus.ACCEPTED);
    assertThat(result.placementPhase()).isEqualTo(PlacementPhase.RISK_COMMITTED);
    assertThat(outbox.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("response-lost debit is found read-only and never reissued")
  void recoverConfirmedDebitByLookup() {
    UUID betId = stalePending("B-2026-05-29-RECON-LK", "recon-lookup");
    stageRiskReserved(betId);
    UUID operationId = UuidV7.generate();
    wireMock.stubFor(
        get(urlEqualTo(DEBIT_PATH + "/" + betId))
            .willReturn(okJson("{\"operationGroupId\":\"" + operationId + "\"}")));
    wireMock.stubFor(
        put(urlEqualTo(RISK_PATH + "/" + betId + "/commit"))
            .willReturn(aResponse().withStatus(204)));

    reconciliation.reconcile();

    Bet result = bets.findById(betId).orElseThrow();
    assertThat(result.status()).isEqualTo(BetStatus.ACCEPTED);
    assertThat(result.walletOperationId()).isEqualTo(operationId);
    wireMock.verify(exactly(0), postRequestedFor(urlEqualTo(DEBIT_PATH)));
    assertThat(outbox.count()).isEqualTo(1);
  }

  @Test
  @DisplayName("insufficient debit rejects only after risk release succeeds")
  void releaseBeforeReject() {
    UUID betId = stalePending("B-2026-05-29-RECON-RL", "recon-release");
    stageRiskReserved(betId);
    wireMock.stubFor(
        get(urlEqualTo(DEBIT_PATH + "/" + betId)).willReturn(aResponse().withStatus(404)));
    wireMock.stubFor(
        post(urlEqualTo(DEBIT_PATH))
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        "{\"code\":\"WALLET_INSUFFICIENT_BALANCE\",\"detail\":\"too low\"}")));
    wireMock.stubFor(
        delete(urlEqualTo(RISK_PATH + "/" + betId)).willReturn(aResponse().withStatus(204)));

    reconciliation.reconcile();

    Bet result = bets.findById(betId).orElseThrow();
    assertThat(result.status()).isEqualTo(BetStatus.REJECTED);
    assertThat(result.rejectionReason()).isEqualTo("INSUFFICIENT_BALANCE");
    assertThat(result.rejectionDetail()).isEqualTo("too low");
    assertThat(outbox.count()).isZero();
  }

  @Test
  @DisplayName("release failure resumes release-only even after wallet balance recovers")
  void releaseFailureCannotResumeDebit() {
    String debitScenario = "wallet-balance-recovers";
    String releaseScenario = "risk-release-retry";
    UUID betId = stalePending("B-2026-05-29-RECON-RX", "recon-release-failure");
    stageRiskReserved(betId);
    wireMock.stubFor(
        get(urlEqualTo(DEBIT_PATH + "/" + betId)).willReturn(aResponse().withStatus(404)));
    wireMock.stubFor(
        post(urlEqualTo(DEBIT_PATH))
            .inScenario(debitScenario)
            .whenScenarioStateIs(STARTED)
            .willSetStateTo("BALANCE_RECOVERED")
            .willReturn(
                aResponse()
                    .withStatus(422)
                    .withHeader("Content-Type", "application/problem+json")
                    .withBody(
                        "{\"code\":\"WALLET_INSUFFICIENT_BALANCE\",\"detail\":\"too low\"}")));
    wireMock.stubFor(
        post(urlEqualTo(DEBIT_PATH))
            .inScenario(debitScenario)
            .whenScenarioStateIs("BALANCE_RECOVERED")
            .willReturn(okJson("{\"operationGroupId\":\"" + UuidV7.generate() + "\"}")));
    wireMock.stubFor(
        delete(urlEqualTo(RISK_PATH + "/" + betId))
            .inScenario(releaseScenario)
            .whenScenarioStateIs(STARTED)
            .willSetStateTo("RETRY")
            .willReturn(aResponse().withStatus(503)));
    wireMock.stubFor(
        delete(urlEqualTo(RISK_PATH + "/" + betId))
            .inScenario(releaseScenario)
            .whenScenarioStateIs("RETRY")
            .willReturn(aResponse().withStatus(204)));

    reconciliation.reconcile();

    Bet deferred = bets.findById(betId).orElseThrow();
    assertThat(deferred.status()).isEqualTo(BetStatus.PENDING);
    assertThat(deferred.placementPhase()).isEqualTo(PlacementPhase.RISK_RESERVED);
    assertThat(deferred.compensationAction()).isEqualTo(CompensationAction.RISK_RELEASE);
    assertThat(deferred.compensationState()).isEqualTo(CompensationState.IN_PROGRESS);
    assertThat(deferred.rejectionReason()).isEqualTo("INSUFFICIENT_BALANCE");

    reconciliation.reconcile();
    reconciliation.reconcile();

    Bet result = bets.findById(betId).orElseThrow();
    assertThat(result.status()).isEqualTo(BetStatus.REJECTED);
    assertThat(result.compensationState()).isEqualTo(CompensationState.COMPLETED);
    assertThat(result.rejectionDetail()).isEqualTo("too low");
    wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(DEBIT_PATH)));
    wireMock.verify(exactly(2), deleteRequestedFor(urlEqualTo(RISK_PATH + "/" + betId)));
    assertThat(outbox.count()).isZero();
  }

  @Test
  @DisplayName("wallet lookup unavailable leaves RISK_RESERVED for another tick")
  void deferWalletLookup() {
    UUID betId = stalePending("B-2026-05-29-RECON-DN", "recon-down");
    stageRiskReserved(betId);
    wireMock.stubFor(
        get(urlEqualTo(DEBIT_PATH + "/" + betId)).willReturn(aResponse().withStatus(503)));

    reconciliation.reconcile();

    Bet result = bets.findById(betId).orElseThrow();
    assertThat(result.status()).isEqualTo(BetStatus.PENDING);
    assertThat(result.placementPhase()).isEqualTo(PlacementPhase.RISK_RESERVED);
    assertThat(outbox.count()).isZero();
  }

  @Test
  @DisplayName("expired reservation denied after debit refunds once and rejects")
  void refundAfterExpiredReservationDenied() {
    UUID betId = stalePending("B-2026-05-29-RECON-RF", "recon-refund");
    stageWalletConfirmed(betId);
    wireMock.stubFor(
        put(urlEqualTo(RISK_PATH + "/" + betId + "/commit"))
            .willReturn(aResponse().withStatus(404)));
    wireMock.stubFor(
        post(urlEqualTo(RISK_PATH))
            .willReturn(okJson("{\"approved\":false,\"rejectionReason\":\"DAILY_STAKE_LIMIT\"}")));
    wireMock.stubFor(
        post(urlEqualTo(CREDIT_PATH))
            .willReturn(okJson("{\"operationGroupId\":\"" + UuidV7.generate() + "\"}")));

    reconciliation.reconcile();
    reconciliation.reconcile();

    Bet result = bets.findById(betId).orElseThrow();
    assertThat(result.status()).isEqualTo(BetStatus.REJECTED);
    assertThat(result.rejectionReason()).isEqualTo("LIMIT_EXCEEDED");
    wireMock.verify(
        exactly(1),
        postRequestedFor(urlEqualTo(CREDIT_PATH))
            .withHeader("Idempotency-Key", equalTo("refund:" + betId)));
    assertThat(outbox.count()).isZero();
  }

  @Test
  @DisplayName(
      "refund committed before a lost response stays refund-only when risk capacity recovers")
  void refundResponseLossCannotReturnToAcceptance() {
    String capacityScenario = "risk-capacity-recovers";
    UUID betId = stalePending("B-2026-05-29-RECON-RA", "recon-refund-ambiguous");
    stageWalletConfirmed(betId);
    wireMock.stubFor(
        put(urlEqualTo(RISK_PATH + "/" + betId + "/commit"))
            .willReturn(aResponse().withStatus(404)));
    wireMock.stubFor(
        post(urlEqualTo(RISK_PATH))
            .inScenario(capacityScenario)
            .whenScenarioStateIs(STARTED)
            .willSetStateTo("CAPACITY_RECOVERED")
            .willReturn(okJson("{\"approved\":false,\"rejectionReason\":\"DAILY_STAKE_LIMIT\"}")));
    wireMock.stubFor(
        post(urlEqualTo(RISK_PATH))
            .inScenario(capacityScenario)
            .whenScenarioStateIs("CAPACITY_RECOVERED")
            .willReturn(
                okJson(
                    "{\"approved\":true,\"reservationState\":\"RESERVED\","
                        + "\"expiresAt\":\"2026-05-29T07:02:00Z\"}")));
    wireMock.stubFor(
        post(urlEqualTo(CREDIT_PATH))
            .willReturn(aResponse().withTransformers(DURABLE_REFUND.getName())));

    reconciliation.reconcile();

    String refundKey = "refund:" + betId;
    Bet deferred = bets.findById(betId).orElseThrow();
    assertThat(deferred.status()).isEqualTo(BetStatus.PENDING);
    assertThat(deferred.placementPhase()).isEqualTo(PlacementPhase.WALLET_CONFIRMED);
    assertThat(deferred.compensationAction()).isEqualTo(CompensationAction.WALLET_REFUND);
    assertThat(deferred.compensationState()).isEqualTo(CompensationState.IN_PROGRESS);
    assertThat(deferred.rejectionReason()).isEqualTo("LIMIT_EXCEEDED");
    assertThat(DURABLE_REFUND.effectCount()).isEqualTo(1);
    assertThat(DURABLE_REFUND.attemptCount(refundKey)).isEqualTo(1);

    reconciliation.reconcile();
    reconciliation.reconcile();

    Bet result = bets.findById(betId).orElseThrow();
    assertThat(result.status()).isEqualTo(BetStatus.REJECTED);
    assertThat(result.compensationState()).isEqualTo(CompensationState.COMPLETED);
    assertThat(result.compensationOperationId()).isEqualTo(DURABLE_REFUND.operation(refundKey));
    assertThat(result.rejectionReason()).isEqualTo("LIMIT_EXCEEDED");
    assertThat(DURABLE_REFUND.effectCount()).isEqualTo(1);
    assertThat(DURABLE_REFUND.attemptCount(refundKey)).isEqualTo(2);
    wireMock.verify(exactly(1), postRequestedFor(urlEqualTo(RISK_PATH)));
    wireMock.verify(
        exactly(2),
        postRequestedFor(urlEqualTo(CREDIT_PATH))
            .withHeader("Idempotency-Key", equalTo(refundKey)));
    assertThat(outbox.count()).isZero();
  }

  /**
   * Minimal durable wallet simulator: the first unique refund key creates one operation and then
   * loses the HTTP response as a 503. Replays return the same operation, exactly like wallet's
   * Idempotency-Key boundary.
   */
  private static final class DurableRefundTransformer implements ResponseDefinitionTransformerV2 {

    private final ConcurrentMap<String, UUID> operations = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> attempts = new ConcurrentHashMap<>();
    private final AtomicInteger effects = new AtomicInteger();

    @Override
    public String getName() {
      return "durable-refund-response-loss";
    }

    @Override
    public boolean applyGlobally() {
      return false;
    }

    @Override
    public ResponseDefinition transform(ServeEvent event) {
      String key = event.getRequest().getHeader("Idempotency-Key");
      UUID candidate = UuidV7.generate();
      UUID existing = operations.putIfAbsent(key, candidate);
      UUID operation = existing == null ? candidate : existing;
      if (existing == null) {
        effects.incrementAndGet();
      }
      int attempt = attempts.computeIfAbsent(key, ignored -> new AtomicInteger()).incrementAndGet();
      if (attempt == 1) {
        return new ResponseDefinition(503, "response lost after durable refund commit");
      }
      return ResponseDefinition.okForJson(Map.of("operationGroupId", operation.toString()));
    }

    int effectCount() {
      return effects.get();
    }

    int attemptCount(String key) {
      AtomicInteger count = attempts.get(key);
      return count == null ? 0 : count.get();
    }

    UUID operation(String key) {
      return operations.get(key);
    }

    void reset() {
      operations.clear();
      attempts.clear();
      effects.set(0);
    }
  }
}
