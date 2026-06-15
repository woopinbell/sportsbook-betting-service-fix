package com.sportsbook.betting.persistence;

import com.sportsbook.betting.placement.PlacementRequest;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for the global placement Idempotency-Key namespace. */
public interface PlacementRequestRepository extends JpaRepository<PlacementRequest, String> {}
