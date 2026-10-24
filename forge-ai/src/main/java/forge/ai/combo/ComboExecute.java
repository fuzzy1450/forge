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
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.Cost;
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
 * <li>DOOM: our Doctor Doom, Unrivaled on the battlefield, untapped and free of summoning sickness, its {T} draw
 * there and not barred (executable's denial reads Linvala, and an ability-removing effect such as Imprisoned in the
 * Moon; Consultation only);
 * <li>LAB, JACE: that winner on our battlefield, holding its Draw replacement that wins (executable's denial reads its
 * loss: Lignify, Humility, a face-down copy), and a draw of ours follows: the opponent's end step with our turn
 * next, or a spell in our hand that the AI casts right after the emptier on the emptied library, read from its shape
 * (a Draw, Scry or RearrangeTopOfLibrary root, every part one whose AI approves it there, an untargeted Draw of ours
 * of a fixed number of cards among them, a cost of mana alone; a sorcery-speed one only in our own main phase; a
 * RemoveDeck-hinted card only on its own readmit row) and the mana for the emptier and that spell together, each
 * one's coloured pips among it.
 * </ul>
 * Pact's own singleton-library condition is executable's. None of it while we cannot win or every opponent cannot lose
 * (executable's denial). Then the draw on the emptied library that finishes the line ({@link #finishesLine}) is played.
 *
 * <p>Nothing here draws a random number, keeps any state of its own (Consultation's tutor intent lives on the
 * tracker) or reads the library's order. The mana for two casts together, Oracle's and its emptier's or an emptier's and
 * the draw spell after it, is read with {@link ComboMana}, which assigns each source to one pip: SpecialCardAi.HonestMana
 * counts one multicolour source toward each of its colours, and would approve Oracle and Consultation on Island,
 * Underground Sea and Sol Ring, where Oracle's {U}{U} takes the Sea and nothing is left for the {B}.
 */
public final class ComboExecute {
    private ComboExecute() {
    }

    /**
     * Whether Thassa's Oracle goes off now (section 6.4): an {@link ComboTracker#executable} line whose winner is Oracle
     * has its emptier in our hand, and the mana we can make now ({@code ComboMana.of(ai, oracleCast, true)}) pays
     * Oracle's cost and the emptier's together, each source assigned to one pip: {U}{U} and Consultation's {B} or Pact's
     * {1}{B}.
     */
    public static boolean goOffNow(final Player ai, final SpellAbility oracleCast) {
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return false;
        }
        ComboMana mana = null;
        for (final ComboLines.Line l : t.executable()) {
            if (l.winner() != ComboLines.Winner.ORACLE) {
                continue;
            }
            final Card emptier = t.find(l.emptier().card, ZoneType.Hand);
            if (emptier == null) {
                continue;
            }
            if (mana == null) {
                mana = ComboMana.of(ai, oracleCast, true);
            }
            if (mana.pays(oracleCast.getHostCard().getManaCost(), emptier.getManaCost())) {
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
     * WillPlay(100) inside a window of a Consultation line; else, last, WillPlay(100) as a tutor
     * ({@link ComboFetch#consultTutor}, section 6.3, which records the piece its resolution names); else null for the
     * stock refusal. Each decision first drops the card's earlier tutor intent, so a Consultation approved as a tutor
     * and cast later inside a window empties the library, as the window's own decision intends.
     */
    public static AiAbilityDecision consultation(final Player ai, final SpellAbility sa) {
        if (!ownHandCast(ai, sa, Emptier.CONSULTATION)) {
            return null;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return null;
        }
        t.takeConsultIntent(sa.getHostCard().getId());
        if (window(ai, t, Emptier.CONSULTATION, sa, true)) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return ComboFetch.consultTutor(ai, sa);
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
     * with Laboratory Maniac or Jace, Wielder of Mysteries ours in play and holding its win (its Draw replacement:
     * none under Lignify, Humility or face down), or {@code sa} is our Doctor Doom's own {T} draw; and a deck line
     * names that winner. Deck lines, not executable ones: the emptier is spent by now.
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

    // our own spell from our hand, not a copy and not a Play-effect cast, whose host is that emptier (ComboFetch's
    // Consultation tutor asks it too)
    static boolean ownHandCast(final Player ai, final SpellAbility sa, final Emptier e) {
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
    // hand that the AI casts right after the emptier on the emptied library (castsOwnDrawAfter) and that the mana pays
    // together with the emptier still to be cast, each source assigned to one pip of the two costs (ComboMana):
    // Consultation's or Pact's {B} beside Opt's {U} (Review Focus 4: two Swamps pay the two mana values, never Opt;
    // Command Tower and Sol Ring make three mana, but one coloured)
    private static boolean drawFollows(final Player ai, final SpellAbility sa, final boolean unpaid) {
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        if (ph.is(PhaseType.END_OF_TURN) && ai.equals(ph.getNextTurn())) {
            return true;
        }
        final ManaCost emptierCost = unpaid ? sa.getHostCard().getManaCost() : ManaCost.ZERO;
        ComboMana mana = null;
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (c.isLand() || !castsOwnDrawAfter(ai, ph, c)) {
                continue;
            }
            if (mana == null) {
                mana = ComboMana.of(ai, sa, true);
            }
            if (mana.pays(emptierCost, c.getManaCost())) {
                return true;
            }
        }
        return false;
    }

    // a spell of the card that the AI casts right after the emptier on the emptied library (Review Focus 4):
    // - of the follow-up shape (followsUp), whose every part the AI approves on the emptied library;
    // - a sorcery-speed spell (SpellAbilityAi.isSorcerySpeed: no flash of its own or from a static) only in our own
    //   main phase; elsewhere it waits past our draw step, which the emptied library loses (Serum Visions in the
    //   opponent's Main 1, in our combat or at our own end step);
    // - a card the AI is never offered, never: getSpellAbilityToPlay drops a RemoveDeck-hinted card's spell unless its
    //   own row readmits it (SpecialCardAi.RemoveDeckFilter.readmit, asked as that filter asks it; See Beyond and
    //   Gitaxian Probe have none)
    private static boolean castsOwnDrawAfter(final Player ai, final PhaseHandler ph, final Card c) {
        final boolean ourMain = ph.isPlayerTurn(ai) && ph.getPhase() != null && ph.getPhase().isMain();
        final boolean hinted = ComputerUtilCard.isCardRemAIDeck(c);
        for (final SpellAbility spell : c.getSpells()) {
            if (!followsUp(c, spell) || (!ourMain && SpellAbilityAi.isSorcerySpeed(spell, ai))) {
                continue;
            }
            if (!hinted || SpecialCardAi.RemoveDeckFilter.readmit(ai, spell)) {
                return true;
            }
        }
        return false;
    }

    // the follow-up shape, read from the spell, never its name: what the AI casts on our emptied library, each part's
    // own AI approving it there
    // - the root a Draw or a Scry, whose refusals of an emptied library finishesLine lifts (DrawAi's deck guard and its
    //   Main 1 hold, ScryAi's phase and empty-library ones), or a RearrangeTopOfLibrary, whose AI approves a spell
    //   outright (Ponder); never a LoseLife (Crushing Disappointment waits for Main 2) nor a Surveil (Consider, Notion
    //   Rain: SurveilAi refuses an empty library);
    // - every part untargeted (finishesLine lifts only an untargeted draw), with no AILogic of its own (the card's own
    //   timing: Whispers of the Muse waits for the opponent's end step) and no UnlessCost (Abandon Attachments draws
    //   only off a discard);
    // - every part a Draw of ours (Defined absent or You: Vision Skeins draws for each player, which finishesLine does
    //   not lift with Jace) of a fixed number of cards (Collective Unconscious draws none with no creature), a Scry, a
    //   RearrangeTopOfLibrary, a ChangeZone from our hand (Brainstorm's put-back, which the hidden-origin drawback
    //   approves; not Inspiring Refrain's suspend from the stack) or a LoseLife (Night's Whisper: LifeLoseAi's drawback
    //   floor is lifted on the finishing draw); not a Discard (Careful Study's looting needs a hand left to discard);
    // - at least one of them that Draw (Index draws nothing);
    // - a cost of mana alone (Thrill of Possibility's discard needs a card left in hand) and no AlternateAdditionalCost
    //   keyword, whose costs only the cast adds (Demand Answers: an artifact to sacrifice or a card to discard)
    private static boolean followsUp(final Card c, final SpellAbility spell) {
        final ApiType root = spell.getApi();
        if (root != ApiType.Draw && root != ApiType.Scry && root != ApiType.RearrangeTopOfLibrary) {
            return false;
        }
        final Cost cost = spell.getPayCosts();
        if (cost == null || !cost.isOnlyManaCost() || c.hasStartOfKeyword("AlternateAdditionalCost")) {
            return false;
        }
        boolean draws = false;
        for (SpellAbility s = spell; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting() || s.hasParam("AILogic") || s.hasParam("UnlessCost")) {
                return false;
            }
            final ApiType api = s.getApi();
            if (api == ApiType.Draw) {
                if (!"You".equals(s.getParamOrDefault("Defined", "You"))
                        || !fixedCount(s.getParamOrDefault("NumCards", "1"))) {
                    return false;
                }
                draws = true;
            } else if (api == ApiType.ChangeZone) {
                if (!"Hand".equals(s.getParam("Origin"))) {
                    return false;
                }
            } else if (api != ApiType.Scry && api != ApiType.RearrangeTopOfLibrary && api != ApiType.LoseLife) {
                return false;
            }
        }
        return draws;
    }

    // a positive whole number written out (NumCards$ 2), not X nor another count
    private static boolean fixedCount(final String n) {
        if (n.isEmpty() || n.charAt(0) == '0') {
            return false;
        }
        for (int i = 0; i < n.length(); i++) {
            if (n.charAt(i) < '0' || n.charAt(i) > '9') {
                return false;
            }
        }
        return true;
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

    // that winner is ours on the battlefield with its Draw replacement that wins, and a deck line names it
    private static boolean inPlay(final ComboTracker t, final Winner w) {
        final Card c = t.find(w.card, ZoneType.Battlefield);
        return c != null && ComboSignals.winsOnDraw(c) && names(t, w);
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
