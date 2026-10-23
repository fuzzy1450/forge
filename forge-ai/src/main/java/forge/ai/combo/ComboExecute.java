package forge.ai.combo;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilCard;
import forge.ai.SpecialCardAi;
import forge.ai.SpellAbilityAi;
import forge.ai.combo.ComboLines.Emptier;
import forge.ai.combo.ComboLines.Winner;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Executing the empty-library family (spec 2026-10-22-combo-aware-ai-core-design.md section 6.4): Thassa's Oracle goes
 * off ({@link #goOffNow}, which releases its hold, {@link ComboHold#vetoesCast}), and an emptier, Demonic Consultation
 * or Tainted Pact, is our own cast only inside a window of one of its lines, each gated by an
 * {@link ComboTracker#executable} line pairing that emptier with that winner:
 * <ul>
 * <li>ORACLE: our Thassa's Oracle trigger is on the stack (Consultation's stock loop; Pact through {@link #pactCast});
 * <li>FBLTHP: our Fblthp, Impossibly Lost trigger is on the stack (Consultation only);
 * <li>DOOM: our Doctor Doom, Unrivaled on the battlefield, untapped and free of summoning sickness, its {T} not barred
 * (executable's denial reads Linvala; Consultation only);
 * <li>LAB, JACE: that winner on our battlefield, and a draw of ours follows: the opponent's end step with our turn
 * next, or a spell in our hand with an untargeted Draw part for us (the finishing draw's own rule) that the AI casts
 * right after the emptier (a sorcery-speed one only in our own main phase; a RemoveDeck-hinted card only on its own
 * readmit row) and the mana for the emptier and that spell together, each one's coloured pips among it.
 * </ul>
 * Pact's own singleton-library condition is executable's. None of it while we cannot win or every opponent cannot lose
 * (executable's denial). Then the draw on the emptied library that finishes the line ({@link #finishesLine}) is played.
 *
 * <p>Nothing here draws a random number, keeps any state or reads the library's order. Mana is read with
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

    /**
     * Our own hand cast of Tainted Pact (RepeatAi.canPlay, its first statements): TRUE, cast it as the emptier, inside an
     * ORACLE, LAB or JACE window of a Pact line; FALSE, hold it, while a {@link ComboTracker#live} Pact line exists;
     * null, the stock cantrip, otherwise, and for any other spell or a deck holding no Pact line.
     */
    public static Boolean pactCast(final Player ai, final SpellAbility sa) {
        if (!ownHandCast(ai, sa, Emptier.PACT)) {
            return null;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || !holds(t, Emptier.PACT)) {
            return null;
        }
        if (window(ai, t, Emptier.PACT, sa, true)) {
            return Boolean.TRUE;
        }
        for (final ComboLines.Line l : t.live()) {
            if (l.emptier() == Emptier.PACT) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /**
     * Whether we decline the card Tainted Pact just exiled (ChangeZoneAi.confirmAction): {@code sa} is Pact's
     * DBReturnToHand, an AbilitySub moving a card from exile to our hand, and an ORACLE, LAB or JACE window of a Pact
     * line holds now. Pact is resolving, so its own mana is paid: a following draw spell needs only its own.
     */
    public static boolean pactDeclines(final Player ai, final SpellAbility sa) {
        if (!(sa instanceof AbilitySub) || sa.getApi() != ApiType.ChangeZone
                || !"Exile".equals(sa.getParam("Origin")) || !"Hand".equals(sa.getParam("Destination"))) {
            return false;
        }
        final Card host = sa.getHostCard();
        if (host == null || !Emptier.PACT.card.equals(ComboTracker.nameOf(host))) {
            return false;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || !holds(t, Emptier.PACT)) {
            return false;
        }
        return window(ai, t, Emptier.PACT, sa, false);
    }

    /**
     * Our own hand cast of Demonic Consultation (SpecialCardAi.DemonicConsultation.consider, after its Oracle loop):
     * WillPlay(100) inside a window of a Consultation line, else null for the stock refusal.
     */
    public static AiAbilityDecision consultation(final Player ai, final SpellAbility sa) {
        if (!ownHandCast(ai, sa, Emptier.CONSULTATION)) {
            return null;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return null;
        }
        if (window(ai, t, Emptier.CONSULTATION, sa, true)) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return null;
    }

    /**
     * Whether the AI answers its own stack top (AiController.getSpellAbilityToPlay's mustRespond): our Fblthp,
     * Impossibly Lost trigger, its Consultation line executable, Demonic Consultation in our hand and black to cast it.
     * Fblthp's script carries no AIRespondsToOwnAbility, so without this the AI lets its own trigger resolve.
     */
    public static boolean respondsToOwn(final Player ai, final SpellAbility top) {
        if (top == null || !top.isTrigger() || !ai.equals(top.getActivatingPlayer())) {
            return false;
        }
        final Card host = top.getHostCard();
        if (host == null || !Winner.FBLTHP.card.equals(ComboTracker.nameOf(host))) {
            return false;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return false;
        }
        final Card consultation = t.find(Emptier.CONSULTATION.card, ZoneType.Hand);
        if (consultation == null) {
            return false;
        }
        for (final ComboLines.Line l : t.executable()) {
            if (l.winner() == Winner.FBLTHP && l.emptier() == Emptier.CONSULTATION) {
                return SpecialCardAi.HonestMana.of(ai, consultation.getFirstSpellAbility(), true)
                        .colour(MagicColor.BLACK) >= 1;
            }
        }
        return false;
    }

    /**
     * Whether {@code sa} is the draw that finishes a line (DrawAi's deck guards and its Main 1 hold): our library is
     * empty, we can win, and either {@code sa} draws for us (itself or a sub: an untargeted Draw for the activator)
     * with Laboratory Maniac or Jace, Wielder of Mysteries ours in play, or {@code sa} is our Doctor Doom's own {T}
     * draw; and a deck line names that winner. Deck lines, not executable ones: the emptier is spent by now.
     */
    public static boolean finishesLine(final Player ai, final SpellAbility sa) {
        if (sa == null || !ai.getCardsIn(ZoneType.Library).isEmpty()) {
            return false;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty() || ai.cantWin()) {
            return false;
        }
        if (drawsForUs(ai, sa) && (inPlay(t, Winner.LAB) || inPlay(t, Winner.JACE))) {
            return true;
        }
        return doomDraw(ai, sa) && names(t, Winner.DOOM);
    }

    /**
     * The own-library floor's exemption for a combo (SpecialCardAi.OwnLibraryFloor.exemption, after its Oracle
     * clause): "combo" when {@code sa} is our own hand cast of an emptier inside a window of one of its lines, else
     * null.
     */
    public static String floorExemption(final Player ai, final SpellAbility sa) {
        final Emptier e = ownHandCast(ai, sa, Emptier.CONSULTATION) ? Emptier.CONSULTATION
                : ownHandCast(ai, sa, Emptier.PACT) ? Emptier.PACT : null;
        if (e == null) {
            return null;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || !holds(t, e)) {
            return null;
        }
        return window(ai, t, e, sa, true) ? "combo" : null;
    }

    // our own spell from our hand, not a copy and not a Play-effect cast, whose host is that emptier
    private static boolean ownHandCast(final Player ai, final SpellAbility sa, final Emptier e) {
        if (sa == null || !sa.isSpell() || sa.isCopied() || sa.isCastFromPlayEffect()) {
            return false;
        }
        final Card host = sa.getHostCard();
        if (host == null || !e.card.equals(ComboTracker.nameOf(host)) || !host.isInZone(ZoneType.Hand)
                || !ai.equals(host.getOwner())) {
            return false;
        }
        final Player activator = sa.getActivatingPlayer();
        return activator == null || ai.equals(activator);
    }

    // whether the deck holds a line of that emptier
    private static boolean holds(final ComboTracker t, final Emptier e) {
        for (final ComboLines.Line l : t.deckLines()) {
            if (l.emptier() == e) {
                return true;
            }
        }
        return false;
    }

    // whether a window of an executable line of emptier e is open now. unpaid: the emptier is still to be cast, so a
    // following draw spell's mana comes on top of its own, its pips among it; false while it resolves (paid already)
    private static boolean window(final Player ai, final ComboTracker t, final Emptier e, final SpellAbility sa,
            final boolean unpaid) {
        for (final ComboLines.Line l : t.executable()) {
            if (l.emptier() == e && open(ai, t, l.winner(), sa, unpaid)) {
                return true;
            }
        }
        return false;
    }

    // the winner's own window (an if chain, not an enum switch: javac would add a synthetic switch-map class)
    private static boolean open(final Player ai, final ComboTracker t, final Winner w, final SpellAbility sa,
            final boolean unpaid) {
        if (w == Winner.ORACLE || w == Winner.FBLTHP) {
            return t.triggerOnOurStack(w.card);
        }
        if (w == Winner.DOOM) {
            final Card doom = t.find(w.card, ZoneType.Battlefield);
            return doom != null && !doom.isTapped() && !doom.isSick();
        }
        return t.find(w.card, ZoneType.Battlefield) != null && drawFollows(ai, sa, unpaid);
    }

    // a draw of ours follows the emptier: the opponent's end step with our turn next (our draw step), or a spell in our
    // hand with an untargeted Draw part for us that the AI casts right after the emptier (castsOwnDrawAfter) and that
    // the mana pays together with the emptier still to be cast: their mana values together, and each colour's pips of
    // the two
    private static boolean drawFollows(final Player ai, final SpellAbility sa, final boolean unpaid) {
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        if (ph.is(PhaseType.END_OF_TURN) && ai.equals(ph.getNextTurn())) {
            return true;
        }
        final int emptierCost = unpaid ? sa.getHostCard().getCMC() : 0;
        final ManaCost emptierPips = unpaid ? sa.getHostCard().getManaCost() : ManaCost.ZERO;
        SpecialCardAi.HonestMana mana = null;
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (c.isLand() || !castsOwnDrawAfter(ai, ph, c)) {
                continue;
            }
            if (mana == null) {
                mana = SpecialCardAi.HonestMana.of(ai, sa, true);
            }
            if (mana.total() >= emptierCost + c.getCMC() && coloursPay(mana, emptierPips, c.getManaCost())) {
                return true;
            }
        }
        return false;
    }

    // the mana holds each colour's pips of the two costs together: Consultation's or Pact's {B} beside Opt's {U}
    // (Review Focus 4: two Swamps pay the two mana values, never Opt). A hybrid or Phyrexian pip counts toward the
    // total only.
    private static boolean coloursPay(final SpecialCardAi.HonestMana mana, final ManaCost a, final ManaCost b) {
        for (final byte colour : MagicColor.WUBRG) {
            final ManaCostShard pip = ManaCostShard.valueOf(colour);
            if (mana.colour(colour) < a.getShardCount(pip) + b.getShardCount(pip)) {
                return false;
            }
        }
        return true;
    }

    // a spell of the card with an untargeted Draw part, the root or a sub, whose Defined is absent or You (drawsForUs's
    // rule, since finishesLine lifts only such a draw: stock DrawAi aims a targeted one at our emptied library only
    // with Laboratory Maniac, never with Jace), that the AI casts right after the emptier (Review Focus 4):
    // - a sorcery-speed spell (SpellAbilityAi.isSorcerySpeed: no flash of its own or from a static) only in our own
    //   main phase; elsewhere it waits past our draw step, which the emptied library loses (Serum Visions in the
    //   opponent's Main 1, in our combat or at our own end step);
    // - a card the AI is never offered, never: getSpellAbilityToPlay drops a RemoveDeck-hinted card's spell unless its
    //   own row readmits it (SpecialCardAi.RemoveDeckFilter.readmit, asked as that filter asks it: Faithless Looting's
    //   row; Gitaxian Probe has none)
    private static boolean castsOwnDrawAfter(final Player ai, final PhaseHandler ph, final Card c) {
        final boolean ourMain = ph.isPlayerTurn(ai) && ph.getPhase() != null && ph.getPhase().isMain();
        final boolean hinted = ComputerUtilCard.isCardRemAIDeck(c);
        for (final SpellAbility spell : c.getSpells()) {
            if (!ownDraw(spell) || (!ourMain && SpellAbilityAi.isSorcerySpeed(spell, ai))) {
                continue;
            }
            if (!hinted || SpecialCardAi.RemoveDeckFilter.readmit(ai, spell)) {
                return true;
            }
        }
        return false;
    }

    // the spell, or a sub of it, is an untargeted Draw whose Defined is absent or You
    private static boolean ownDraw(final SpellAbility spell) {
        for (SpellAbility s = spell; s != null; s = s.getSubAbility()) {
            if (s.getApi() == ApiType.Draw && !s.usesTargeting()
                    && "You".equals(s.getParamOrDefault("Defined", "You"))) {
                return true;
            }
        }
        return false;
    }

    // sa, or a sub of it, draws for its activator, who is us: an untargeted Draw whose Defined is absent or You
    private static boolean drawsForUs(final Player ai, final SpellAbility sa) {
        final Player activator = sa.getActivatingPlayer();
        return (activator == null || ai.equals(activator)) && ownDraw(sa);
    }

    // sa is the {T} draw of a Doctor Doom, Unrivaled we control
    private static boolean doomDraw(final Player ai, final SpellAbility sa) {
        final Card host = sa.getHostCard();
        return host != null && Winner.DOOM.card.equals(ComboTracker.nameOf(host)) && ai.equals(host.getController())
                && sa.isActivatedAbility() && sa.getApi() == ApiType.Draw && sa.getPayCosts() != null
                && sa.getPayCosts().hasTapCost();
    }

    // that winner is ours on the battlefield and a deck line names it
    private static boolean inPlay(final ComboTracker t, final Winner w) {
        return t.find(w.card, ZoneType.Battlefield) != null && names(t, w);
    }

    // a deck line names that winner
    private static boolean names(final ComboTracker t, final Winner w) {
        for (final ComboLines.Line l : t.deckLines()) {
            if (l.winner() == w) {
                return true;
            }
        }
        return false;
    }
}
