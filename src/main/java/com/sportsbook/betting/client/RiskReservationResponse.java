package com.sportsbook.betting.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** Response from the atomic risk reservation endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RiskReservationResponse(
    boolean approved, String rejectionReason, String reservationState, Instant expiresAt) {}
