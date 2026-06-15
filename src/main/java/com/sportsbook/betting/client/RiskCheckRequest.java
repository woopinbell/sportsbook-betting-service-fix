package com.sportsbook.betting.client;

import com.sportsbook.protocol.value.Money;
import java.util.List;

/**
 * Wire body shared by the diagnostic check and atomic {@code /risk/reservations} endpoint. Betting
 * owns its copy of this service-to-service contract; {@code Money} comes from shared-protocol.
 */
public record RiskCheckRequest(
    String userId, String betId, Money stake, List<String> selectionIds) {}
