package com.sportsbook.betting.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sportsbook.betting.api.CursorPage;
import com.sportsbook.betting.domain.Bet;
import com.sportsbook.betting.error.BetNotFoundException;
import com.sportsbook.betting.persistence.BetRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class BetQueryServiceTest {

  private final BetRepository bets = mock(BetRepository.class);
  private final BetQueryService query = new BetQueryService(bets);

  @Test
  @DisplayName("single lookup hides another actor's bet exactly like an absent bet")
  void byIdIsActorScoped() {
    UUID owner = UUID.randomUUID();
    UUID betId = UUID.randomUUID();
    UUID missingBetId = UUID.randomUUID();
    Bet bet = mock(Bet.class);
    when(bet.userId()).thenReturn(owner);
    when(bets.findWithLegsByBetId(betId)).thenReturn(Optional.of(bet));
    when(bets.findWithLegsByBetId(missingBetId)).thenReturn(Optional.empty());

    assertThat(query.byId(owner, betId)).isSameAs(bet);
    assertThatThrownBy(() -> query.byId(UUID.randomUUID(), betId))
        .isInstanceOf(BetNotFoundException.class)
        .hasMessage("No bet with id " + betId);
    assertThatThrownBy(() -> query.byId(owner, missingBetId))
        .isInstanceOf(BetNotFoundException.class)
        .hasMessage("No bet with id " + missingBetId);
  }

  @Test
  @DisplayName("history lookup uses the verified actor as the repository owner key")
  void pageIsActorScoped() {
    UUID actor = UUID.randomUUID();
    when(bets.findByUserIdOrderByBetIdDesc(any(), any())).thenReturn(List.of());

    CursorPage<Bet> page = query.page(actor, null, null);

    assertThat(page.items()).isEmpty();
    verify(bets)
        .findByUserIdOrderByBetIdDesc(org.mockito.ArgumentMatchers.eq(actor), any(Pageable.class));
  }
}
