package com.sportsbook.betting.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sportsbook.betting.error.DependencyUnavailableException;
import com.sportsbook.betting.error.DuplicateBetException;
import com.sportsbook.betting.error.RiskLimitException;
import com.sportsbook.betting.infrastructure.id.UuidV7;
import com.sportsbook.protocol.value.Money;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

/** Response-translation coverage for {@link RiskClient} against a WireMock risk-service stub. */
class RiskClientTest {

  private static final String RESERVATIONS_PATH = "/internal/v1/risk/reservations";
  private static final UUID BET = UuidV7.generate();
  private static final UUID USER = UuidV7.generate();
  private static final Money STAKE = Money.krw(10_000);
  private static final List<String> SELECTIONS = List.of(UuidV7.generate().toString());

  private static WireMockServer wm;

  @BeforeAll
  static void startServer() {
    wm = new WireMockServer(options().dynamicPort());
    wm.start();
  }

  @AfterAll
  static void stopServer() {
    wm.stop();
  }

  @BeforeEach
  void reset() {
    wm.resetAll();
  }

  private RiskClient client(Duration readTimeout) {
    ClientHttpRequestFactorySettings settings =
        ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(Duration.ofMillis(300))
            .withReadTimeout(readTimeout);
    RestClient http =
        RestClient.builder()
            .baseUrl(wm.baseUrl())
            .requestFactory(ClientHttpRequestFactories.get(settings))
            .build();
    return new RiskClient(http);
  }

  @Test
  @DisplayName("approved reservation -> returns state and expiry")
  void approved() {
    wm.stubFor(
        post(urlEqualTo(RESERVATIONS_PATH))
            .willReturn(
                okJson(
                    "{\"approved\":true,\"reservationState\":\"RESERVED\","
                        + "\"expiresAt\":\"2026-05-29T07:02:00Z\"}")));

    assertThatCode(() -> client(Duration.ofMillis(500)).reserve(BET, USER, STAKE, SELECTIONS))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("approved:false (HTTP 200) -> RiskLimitException, not a breaker failure")
  void declined() {
    wm.stubFor(
        post(urlEqualTo(RESERVATIONS_PATH))
            .willReturn(okJson("{\"approved\":false,\"rejectionReason\":\"DAILY_STAKE_LIMIT\"}")));

    assertThatThrownBy(() -> client(Duration.ofMillis(500)).reserve(BET, USER, STAKE, SELECTIONS))
        .isInstanceOf(RiskLimitException.class)
        .hasMessageContaining("DAILY_STAKE_LIMIT");
  }

  @Test
  @DisplayName("payload conflict -> DuplicateBetException")
  void conflict() {
    wm.stubFor(post(urlEqualTo(RESERVATIONS_PATH)).willReturn(aResponse().withStatus(409)));

    assertThatThrownBy(() -> client(Duration.ofMillis(500)).reserve(BET, USER, STAKE, SELECTIONS))
        .isInstanceOf(DuplicateBetException.class);
  }

  @Test
  @DisplayName("5xx -> DependencyUnavailableException (ambiguous)")
  void serverError() {
    wm.stubFor(post(urlEqualTo(RESERVATIONS_PATH)).willReturn(aResponse().withStatus(500)));

    assertThatThrownBy(() -> client(Duration.ofMillis(500)).reserve(BET, USER, STAKE, SELECTIONS))
        .isInstanceOf(DependencyUnavailableException.class);
  }

  @Test
  @DisplayName("read timeout -> DependencyUnavailableException (ambiguous/recoverable)")
  void timeout() {
    wm.stubFor(
        post(urlEqualTo(RESERVATIONS_PATH))
            .willReturn(
                okJson("{\"approved\":true,\"reservationState\":\"RESERVED\"}")
                    .withFixedDelay(1_000)));

    assertThatThrownBy(() -> client(Duration.ofMillis(150)).reserve(BET, USER, STAKE, SELECTIONS))
        .isInstanceOf(DependencyUnavailableException.class);
  }

  @Test
  @DisplayName("commit 204 succeeds while missing/expired 404 is explicit")
  void commitLifecycle() {
    String path = RESERVATIONS_PATH + "/" + BET + "/commit";
    wm.stubFor(put(urlEqualTo(path)).willReturn(aResponse().withStatus(204)));
    org.assertj.core.api.Assertions.assertThat(client(Duration.ofMillis(500)).commit(BET)).isTrue();

    wm.resetAll();
    wm.stubFor(put(urlEqualTo(path)).willReturn(aResponse().withStatus(404)));
    org.assertj.core.api.Assertions.assertThat(client(Duration.ofMillis(500)).commit(BET))
        .isFalse();
  }

  @Test
  @DisplayName("release 204 succeeds")
  void release() {
    wm.stubFor(
        delete(urlEqualTo(RESERVATIONS_PATH + "/" + BET)).willReturn(aResponse().withStatus(204)));

    assertThatCode(() -> client(Duration.ofMillis(500)).release(BET)).doesNotThrowAnyException();
  }
}
