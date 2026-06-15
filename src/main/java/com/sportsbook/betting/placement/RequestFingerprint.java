package com.sportsbook.betting.placement;

import com.sportsbook.betting.placement.PlaceBetCommand.SelectionInput;
import com.sportsbook.protocol.domain.BetSlipType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Produces a stable SHA-256 fingerprint for Idempotency-Key payload conflict detection. */
final class RequestFingerprint {

  private RequestFingerprint() {}

  static String of(PlaceBetCommand command) {
    StringBuilder canonical =
        new StringBuilder()
            .append(command.userId())
            .append('|')
            .append(slipType(command.slipType()))
            .append('|')
            .append(command.unitStake().amount())
            .append('|')
            .append(command.unitStake().currency());
    for (SelectionInput selection : command.selections()) {
      canonical
          .append('|')
          .append(selection.eventId())
          .append('|')
          .append(selection.marketId())
          .append('|')
          .append(selection.selectionId())
          .append('|')
          .append(selection.oddsAtSubmission().decimal().stripTrailingZeros().toPlainString());
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of()
          .formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static String slipType(BetSlipType slipType) {
    if (slipType instanceof BetSlipType.System system) {
      return "SYSTEM:" + system.minWins() + ':' + system.totalSelections();
    }
    return slipType instanceof BetSlipType.Single ? "SINGLE" : "MULTIPLE";
  }
}
