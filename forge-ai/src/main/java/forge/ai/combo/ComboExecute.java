package forge.ai.combo;

import forge.ai.SpecialCardAi;
import forge.card.MagicColor;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Executing the empty-library family (spec 2026-10-22-combo-aware-ai-core-design.md section 6.4). For now only
 * {@link #goOffNow}, which releases Thassa's Oracle's hold ({@link ComboHold#vetoesCast}).
 *
 * <p>Nothing here draws a random number, keeps any state or reads the library's order. The go-off mana is read with
 * SpecialCardAi.HonestMana, whose known over-count is that one multicolour land counts toward each of its colours.
 */
public final class ComboExecute {
    /** Thassa's Oracle's mana value, {U}{U}: the first of the go-off's two casts. */
    private static final int ORACLE_COST = 2;

    private ComboExecute() {
    }

    /**
     * Whether Thassa's Oracle goes off now (section 6.4): an {@link ComboTracker#executable} line whose winner is Oracle
     * has its emptier in our hand, and the mana we can make now ({@code HonestMana.of(ai, oracleCast, true)}) pays for
     * both, Oracle's two and the emptier's mana value, with two blue and one black among it.
     */
    public static boolean goOffNow(final Player ai, final SpellAbility oracleCast) {
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return false;
        }
        SpecialCardAi.HonestMana mana = null;
        for (final ComboLines.Line l : t.executable()) {
            if (l.winner() != ComboLines.Winner.ORACLE) {
                continue;
            }
            final Card emptier = t.find(l.emptier().card, ZoneType.Hand);
            if (emptier == null) {
                continue;
            }
            if (mana == null) {
                mana = SpecialCardAi.HonestMana.of(ai, oracleCast, true);
            }
            if (mana.total() >= ORACLE_COST + emptier.getCMC() && mana.colour(MagicColor.BLUE) >= 2
                    && mana.colour(MagicColor.BLACK) >= 1) {
                return true;
            }
        }
        return false;
    }
}
