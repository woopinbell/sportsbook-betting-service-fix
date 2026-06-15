package com.sportsbook.betting.api;

import com.sportsbook.betting.api.PlaceBetRequest.SelectionRequest;
import com.sportsbook.betting.api.PlaceBetRequest.SlipTypeRequest;
import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.error.ForbiddenException;
import com.sportsbook.betting.placement.BetPlacementService;
import com.sportsbook.betting.placement.BetQueryService;
import com.sportsbook.betting.placement.PlaceBetCommand;
import com.sportsbook.betting.placement.PlaceBetCommand.SelectionInput;
import com.sportsbook.protocol.domain.BetSlipType;
import com.sportsbook.protocol.domain.BetStatus;
import com.sportsbook.protocol.value.IdempotencyKey;
import com.sportsbook.protocol.value.Odds;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal REST surface for bet placement and lookup (ADR-0004, {@code /internal/v1} prefix;
 * gateway is the public front door). Domain / validation exceptions become RFC 7807 responses via
 * {@link BetExceptionHandler}.
 */
@RestController
@RequestMapping("/internal/v1/bets")
public class BetController {

  private static final String ACTOR_HEADER = "X-User-Id";

  private final BetPlacementService placement;
  private final BetQueryService query;

  public BetController(BetPlacementService placement, BetQueryService query) {
    this.placement = placement;
    this.query = query;
  }

  @ModelAttribute
  void requireActorBeforeBinding(
      @RequestHeader(value = ACTOR_HEADER, required = false) String actorHeader) {
    requireActor(actorHeader);
  }

  /** Places a slip. Completed acceptance is 201; recoverable ambiguity is 202 with a Location. */
  @PostMapping
  public ResponseEntity<BetResponse> place(
      @RequestHeader(value = ACTOR_HEADER, required = false) String actorHeader,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @Valid @RequestBody PlaceBetRequest request) {
    UUID actor = requireActor(actorHeader);
    requireSameUser(actor, request.userId());
    Bet bet = placement.place(toCommand(actor, request, idempotencyKey));
    // The service is called through gateway in public flows. Expose a Location the client can
    // follow instead of leaking the service-only /internal/v1 route.
    URI location = URI.create("/api/v1/bets/" + bet.betId());
    if (bet.status() == BetStatus.PENDING) {
      return ResponseEntity.accepted().location(location).body(BetResponse.from(bet));
    }
    return ResponseEntity.created(location).body(BetResponse.from(bet));
  }

  @GetMapping("/{betId}")
  public BetResponse get(
      @RequestHeader(value = ACTOR_HEADER, required = false) String actorHeader,
      @PathVariable UUID betId) {
    return BetResponse.from(query.byId(requireActor(actorHeader), betId));
  }

  /** A user's bets, newest first, keyset-paginated by betId (ADR-0004 cursor pagination). */
  @GetMapping
  public CursorPage<BetResponse> list(
      @RequestHeader(value = ACTOR_HEADER, required = false) String actorHeader,
      @RequestParam UUID userId,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(required = false) Integer limit) {
    UUID actor = requireActor(actorHeader);
    requireSameUser(actor, userId);
    CursorPage<Bet> page = query.page(actor, cursor, limit);
    List<BetResponse> items = page.items().stream().map(BetResponse::from).toList();
    return new CursorPage<>(items, page.nextCursor(), page.hasMore());
  }

  private static UUID requireActor(String actorHeader) {
    if (actorHeader == null || actorHeader.isBlank()) {
      throw new ForbiddenException("A valid X-User-Id actor is required");
    }
    try {
      UUID actor = UUID.fromString(actorHeader);
      if (!actor.toString().equalsIgnoreCase(actorHeader)) {
        throw new ForbiddenException("A valid X-User-Id actor is required");
      }
      return actor;
    } catch (IllegalArgumentException invalid) {
      throw new ForbiddenException("A valid X-User-Id actor is required");
    }
  }

  private static void requireSameUser(UUID actor, UUID requestedUser) {
    if (!actor.equals(requestedUser)) {
      throw new ForbiddenException("The authenticated actor does not match the requested user");
    }
  }

  private static PlaceBetCommand toCommand(
      UUID actor, PlaceBetRequest request, String idempotencyKey) {
    List<SelectionInput> selections =
        request.selections().stream().map(BetController::toSelection).toList();
    return new PlaceBetCommand(
        actor,
        toSlipType(request.slipType()),
        selections,
        request.stake(),
        IdempotencyKey.of(idempotencyKey));
  }

  private static SelectionInput toSelection(SelectionRequest selection) {
    return new SelectionInput(
        selection.eventId(),
        selection.marketId(),
        selection.selectionId(),
        Odds.ofDecimal(selection.odds()));
  }

  private static BetSlipType toSlipType(SlipTypeRequest slipType) {
    return switch (slipType.type().toUpperCase(Locale.ROOT)) {
      case "SINGLE" -> new BetSlipType.Single();
      case "MULTIPLE" -> new BetSlipType.Multiple();
      case "SYSTEM" ->
          new BetSlipType.System(
              required(slipType.minWins(), "minWins"),
              required(slipType.totalSelections(), "totalSelections"));
      default -> throw new IllegalArgumentException("Unknown slip type: " + slipType.type());
    };
  }

  private static int required(Integer value, String name) {
    if (value == null) {
      throw new IllegalArgumentException("SYSTEM slip requires " + name);
    }
    return value;
  }
}
