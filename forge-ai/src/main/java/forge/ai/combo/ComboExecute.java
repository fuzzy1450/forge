package forge.ai.combo;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.Iterables;

import forge.ai.AiAbilityDecision;
import forge.ai.AiCardMemory;
import forge.ai.AiCostDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilMana;
import forge.ai.SpecialCardAi;
import forge.ai.SpellAbilityAi;
import forge.ai.combo.ComboLines.Emptier;
import forge.ai.combo.ComboLines.Winner;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostAdjustment;
import forge.game.cost.CostPayment;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * Executing the empty-library family (spec 2026-10-22-combo-aware-ai-core-design.md section 6.4): Thassa's Oracle goes
 * off ({@link #goOffNow}, which releases its hold, {@link ComboHold#vetoesCast}, lifts its Main-2 wait in our Main 1,
 * {@link #goOffInMainOne}, and puts its cast ahead of every other spell at that priority pass, {@link #goOffFirst}),
 * and an emptier, Demonic Consultation
 * or Tainted Pact, is our own cast only inside a window of one of its lines, each gated by a line executable now (the
 * view's executableNow: denied as read now, the window's own moment) pairing that emptier with that winner:
 * <ul>
 * <li>ORACLE: our Thassa's Oracle trigger is on the stack (Consultation's stock loop; Pact through {@link #pactCast});
 * <li>FBLTHP: our Fblthp, Impossibly Lost trigger is on the stack (Consultation only);
 * <li>DOOM: our Doctor Doom, Unrivaled on the battlefield, untapped and free of summoning sickness, its {T} draw
 * there and not barred (executable's denial reads Linvala, and an ability-removing effect such as Imprisoned in the
 * Moon; Consultation only);
 * <li>LAB, JACE: that winner on our battlefield, holding its Draw replacement that wins (executable's denial reads its
 * loss: Lignify, Humility, a face-down copy), and a draw of ours follows. While nothing but the emptier's own spell is
 * on the stack (follow-up 4, I1: at the emptier's cast an empty stack, and while Tainted Pact resolves only Pact
 * itself, still the stack's top), that draw is the opponent's end step with our turn next, or a spell in our hand that
 * the AI casts right after the emptier on the emptied library. Above anything else on the stack, ours or an opponent's,
 * it is only such a spell cast at instant speed right after the emptier resolves, above what remains, at no sorcery's
 * moment (follow-up 5, J1: so the opponent's Teferi, Time Raveler bars Opt there): no emptier is cast in response to an
 * opponent's Swords to Plowshares on our Laboratory Maniac for a Serum Visions that could follow only once Swords has
 * exiled Lab, while with Opt it is, and Opt wins above Swords; the three windows above read no such rule. The spell is
 * read from its shape (a Draw, Scry or RearrangeTopOfLibrary root, every part one whose AI approves it there, an
 * untargeted Draw of ours of a fixed number of cards among them, a cost of mana alone, whose pricing neither asks the
 * AI's controller nor test-pays, follow-up 3, H1: no Convoke, Improvise, Delve, Assist, waterbend or
 * TapCreaturesForMana, each a question to the controller, and no Announce$ NumTimes, which pricing test-pays, a payment
 * tried rather than a question asked; a sorcery-speed one only in our own main phase with nothing beneath the emptier;
 * a RemoveDeck-hinted card only on its own readmit row), the mana for the emptier and that spell together, each one's
 * coloured pips among it, and that spell castable right after the emptier (follow-up 3, H3: its own cast bar, the
 * emptier counted among the turn's spells, so Rule of Law and Deafening Silence close the window).
 * </ul>
 * Pact's own singleton-library condition is executable's. None of it while we cannot win or every opponent cannot lose
 * (executable's denial). Then the draw on the emptied library that finishes the line ({@link #finishesLine}) is played.
 * The go-off reads {@link ComboTracker#executable}, whose denial reads the emptier at the moment Oracle's trigger will
 * be on the stack (follow-up F3), and that same moment this turn (follow-up 2, G2: a line live for a later turn goes
 * off in none this turn): Oracle is cast before its window opens.
 *
 * <p>Nothing here draws a random number, keeps any state of its own (Consultation's tutor intent lives on the
 * tracker) or reads the library's order. The mana for two casts together, Oracle's and its emptier's or an emptier's and
 * the draw spell after it, is read with {@link ComboMana}, which assigns each source to one pip: SpecialCardAi.HonestMana
 * counts one multicolour source toward each of its colours, and would approve Oracle and Consultation on Island,
 * Underground Sea and Sol Ring, where Oracle's {U}{U} takes the Sea and nothing is left for the {B}. Each of those
 * costs is the spell's own as the engine prices it now ({@link #priced}, follow-up 2, G3: Thalia, Guardian of Thraben,
 * Sphere of Resistance and Grand Arbiter Augustin IV raise it, Helm of Awakening lowers it), and while a go-off is under
 * way an opponent's Rhystic Study is not paid when its {1} would leave no line's emptier payable, and else is paid with
 * the emptier's sources held from its payer ({@link #payUnlessCost}, follow-up 3, H4).
 */
public final class ComboExecute {
    private ComboExecute() {
    }

    /**
     * Whether Thassa's Oracle goes off now (section 6.4): an {@link ComboTracker#executable} line whose winner is
     * Oracle, not denied this turn (its emptier castable at Oracle's trigger this turn), has its emptier in our hand,
     * and the mana we can make now ({@code ComboMana.of(ai, oracleCast, true)}) pays Oracle's cost and the emptier's
     * together, each source assigned to one pip: {U}{U} and Consultation's {B} or Pact's {1}{B}, each cost as the engine
     * prices it now ({@link #priced}, follow-up 2, G3: under an opponent's Thalia, Guardian of Thraben, Consultation's
     * {1}{B}).
     */
    public static boolean goOffNow(final Player ai, final SpellAbility oracleCast) {
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || t.deckLines().isEmpty()) {
            return false;
        }
        return goOffNow(ai, oracleCast, t.view());
    }

    /**
     * The go-off goes first (follow-up F5; AiController.getSpellAbilityToPlay, ahead of its walk): among
     * {@code spells}, what the AI may play now, our own hand cast of Thassa's Oracle (not a copy, not a Play-effect
     * cast) when {@link #goOffNow} holds for it, on an empty stack; else null. The caller plays it when
     * canPlayAndPayFor approves it (in our Main 1 too: {@link #goOffInMainOne} lifts the creature's wait for Main 2),
     * so no spell that the walk sorts ahead of it (Divination beside Oracle's two mana
     * and a creature's one; a Wind Drake the creature sort prefers), nor a pre-cast after the walk, spends the
     * go-off's mana first and holds the Oracle again. A go-off starts on an empty stack; with our Oracle's trigger on
     * the stack it is under way, and the emptier's window casts the emptier. The cast returned has us as its
     * activator, the walk's own first step for every spell it reads. Null at once for a deck holding no line that
     * Thassa's Oracle wins.
     */
    public static SpellAbility goOffFirst(final Player ai, final List<SpellAbility> spells) {
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || !names(t, Winner.ORACLE) || !ai.getGame().getStack().isEmpty()) {
            return null;
        }
        ComboTracker.View v = null;
        for (final SpellAbility sa : spells) {
            if (!ownHandCast(ai, sa, Winner.ORACLE.card)) {
                continue;
            }
            if (v == null) {
                v = t.view();
            }
            if (goOffNow(ai, sa, v)) {
                sa.setActivatingPlayer(ai);
                return sa;
            }
        }
        return null;
    }

    /**
     * Whether Thassa's Oracle's Main-2 wait is lifted (follow-up 2, G1; PermanentCreatureAi.checkPhaseRestrictions,
     * beside Neera's commander lift, ahead of PermanentAi's wait for Main 2): our own hand cast of Oracle (not a copy,
     * not a Play-effect cast) in our Main 1 on an empty stack, while {@link #goOffNow} holds for it. The go-off
     * sequencer ({@link #goOffFirst}) then has Oracle approved there, ahead of the walk, so no Main-1 play (Rampant
     * Growth, Carpet of Flowers) spends the go-off's mana first and leaves it for a turn. False at once for any other
     * card and for a deck holding no line that Thassa's Oracle wins; then, and while the go-off is not ready, the
     * stock wait answers as before. Draws nothing.
     */
    public static boolean goOffInMainOne(final Player ai, final SpellAbility sa) {
        if (!ownHandCast(ai, sa, Winner.ORACLE.card)) {
            return false;
        }
        if (!ai.getGame().getPhaseHandler().is(PhaseType.MAIN1, ai) || !ai.getGame().getStack().isEmpty()) {
            return false;
        }
        final ComboTracker t = ComboTracker.of(ai);
        return t != null && names(t, Winner.ORACLE) && goOffNow(ai, sa, t.view());
    }

    /** {@link #goOffNow}, read through the decision's view (ComboHold.vetoesCast's, which read its live lines). The
     *  go-off comes this turn, so its line's denial is the one this turn (the view's deniedThisTurn, follow-up 2, G2):
     *  denied now, or its emptier barred at Oracle's trigger this turn. An executable line whose window this turn is
     *  barred (Deafening Silence after a noncreature spell this turn) waits for a later turn, Oracle held for it. */
    static boolean goOffNow(final Player ai, final SpellAbility oracleCast, final ComboTracker.View v) {
        ComboMana mana = null;
        ManaCost oracle = null;
        for (final ComboLines.Line l : v.executable()) {
            if (l.winner() != ComboLines.Winner.ORACLE || v.deniedThisTurn(l)) {
                continue;
            }
            final SpellAbility emptier = handSpell(v, l.emptier());
            if (emptier == null) {
                continue;
            }
            if (mana == null) {
                mana = ComboMana.of(ai, oracleCast, true);
                oracle = priced(ai, oracleCast);
            }
            if (mana.pays(oracle, priced(ai, emptier))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Our payment of an unless cost now (follow-up 2, G3; follow-up 3, H4; PlayerControllerAi.payCostToPreventEffect,
     * once the stock decision would pay it and canPayCost finds it payable), while a go-off is under way (our Thassa's
     * Oracle cast and still on our stack, or its entering trigger there) and the cost only stops a draw for opponents
     * of ours ({@link #opponentsDraw}: an opponent's Rhystic Study, "you may draw a card unless that player pays {1}").
     * Each line of {@link #goOffNow}'s (executable, Oracle its winner, not denied this turn) with its emptier in our
     * hand, whose engine price the mana we can make now pays alone, is read in turn:
     * <ul>
     * <li>kept, when some of our sources pay that emptier alone while the rest and the floating pool pay the unless
     * cost's mana ({@link ComboMana#hold}): the first kept line's sources are held from the payer (payHolding) and the
     * cost is paid, TRUE when it is. The stock payer would choose its own sources, and for Rhystic's draw its
     * tie-break reads the opponent's hand: with a Swamp and a Forest open and a green hand there, it pays the {1} with
     * the Swamp, and Consultation cannot follow;
     * <li>broken, otherwise. FALSE, declined, when a line is broken and none is kept: the opponent draws a card, the
     * emptier is cast and the combo wins. A line broken beside one kept (Tainted Pact's {1}{B} beside Consultation's
     * {B} on two Swamps) declines nothing.
     * </ul>
     * Null, the stock payment, for every other unless cost (the opponent's Mana Leak on our Oracle: declined, the Oracle
     * would be countered), outside a go-off, and when no line's emptier is payable even alone; null at once for a deck
     * holding no line that Thassa's Oracle wins. Nothing here draws; the payment draws as the stock one does, but for a
     * held source, which its payer refuses before it would draw.
     */
    public static Boolean payUnlessCost(final Player ai, final SpellAbility sa, final Cost cost) {
        if (sa == null || cost == null || !cost.hasManaCost()) {
            return null;
        }
        final ComboTracker t = ComboTracker.of(ai);
        if (t == null || !names(t, Winner.ORACLE) || !opponentsDraw(ai, sa)) {
            return null;
        }
        final ComboTracker.View v = t.view();
        if (!v.triggerOnOurStack(Winner.ORACLE.card) && !oracleCastOnOurStack(ai)) {
            return null;
        }
        ManaCost unless = null;
        ComboMana payment = null;
        boolean broken = false;
        for (final ComboLines.Line l : v.executable()) {
            if (l.winner() != Winner.ORACLE || v.deniedThisTurn(l)) {
                continue;
            }
            final SpellAbility emptier = handSpell(v, l.emptier());
            if (emptier == null) {
                continue;
            }
            final ComboMana mana = ComboMana.of(ai, emptier, true);
            final ManaCost emptierCost = priced(ai, emptier);
            if (!mana.pays(emptierCost)) {
                continue;
            }
            if (unless == null) {
                // the unless cost as the payment prices it (canPayCost's effect-mode reading: no taxes, X counted),
                // and the mana that may pay it, read for sa as the payer reads its sources
                unless = ComputerUtilMana.calculateManaCost(cost, sa, ai, true, 0, true).toManaCost();
                payment = ComboMana.of(ai, sa, true);
            }
            final List<Card> hold = mana.hold(emptierCost, payment, unless);
            if (hold != null) {
                return payHolding(ai, sa, cost, hold);
            }
            broken = true;
        }
        return broken ? Boolean.FALSE : null;
    }

    // the stock payment of the unless cost (CostPayment with the AI's own cost decisions, as an effect), the sources in
    // hold held from its payer for this payment alone: remembered in HELD_MANA_SOURCES_FOR_NEXT_SPELL, which
    // ComputerUtilMana.isManaSourceReserved refuses before anything else (Ride the Avalanche's hold for the spell it
    // rides in front of), and forgotten in a finally right after it, so no hold outlives the payment; a source held
    // there already stays held
    private static boolean payHolding(final Player ai, final SpellAbility sa, final Cost cost, final List<Card> hold) {
        final List<Card> added = new ArrayList<>(hold.size());
        try {
            for (final Card c : hold) {
                if (!AiCardMemory.isRememberedCard(ai, c, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL)) {
                    AiCardMemory.rememberCard(ai, c, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
                    added.add(c);
                }
            }
            return new CostPayment(cost, sa).payComputerCosts(new AiCostDecision(ai, sa, true));
        } finally {
            for (final Card c : added) {
                AiCardMemory.forgetCard(ai, c, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL);
            }
        }
    }

    // the cost of our cast of spell as the engine prices it now: test-mode calculateManaCost on the spell's own cost
    // (SpecialCardAi.VampiricTutor.consider's precedent), taxes and reductions included (Thalia, Guardian of Thraben;
    // Sphere of Resistance; Grand Arbiter Augustin IV; Helm of Awakening). Nothing drawn: no caller prices a spell whose
    // price asks the AI's controller or test-pays (pricingAsks, follow-up 3, H1). followUpSpell refuses such a draw, and
    // the emptiers' and winners' scripts carry no such step (every script that grants Convoke, Improvise, Delve, Assist
    // or Emerge grants it to a spell on the stack only, while priced reads a card in our hand, library or command zone).
    // We are made the spell's activator first, as the AI's own walk makes us every spell's
    // (ComputerUtilAbility.getOriginalAndAltCostAbilities)
    static ManaCost priced(final Player ai, final SpellAbility spell) {
        spell.setActivatingPlayer(ai);
        return ComputerUtilMana.calculateManaCost(spell.getPayCosts(), spell, ai, true, 0, false).toManaCost();
    }

    // the spell of our card of the emptier e in our hand, its first (Consultation and Pact have one); null when no card
    // of it is in our hand
    private static SpellAbility handSpell(final ComboTracker.View v, final Emptier e) {
        if (v.where(e.card) != ComboTracker.Where.HAND) {
            return null;
        }
        return Iterables.getFirst(v.card(e.card).getSpells(), null);
    }

    // our Thassa's Oracle cast and still on our stack: its emptier is still to be cast after its trigger
    private static boolean oracleCastOnOurStack(final Player ai) {
        for (final SpellAbilityStackInstance si : ai.getGame().getStack()) {
            final Card host = si.getSourceCard();
            if (si.isSpell() && host != null && ai.equals(si.getActivatingPlayer())
                    && Winner.ORACLE.card.equals(ComboTracker.nameOf(host))) {
                return true;
            }
        }
        return false;
    }

    // the unless cost only stops a draw for opponents of ours, which cannot stop a go-off: sa is a Draw, untargeted,
    // with no sub and no UnlessSwitched (paying would make it happen), and every player it draws for (Defined, else its
    // activator) is an opponent of ours (Rhystic Study, Mystic Remora and Esper Sentinel, whose controller draws). Not a
    // draw for us, which on an empty library would lose the game before Oracle's trigger resolves; not a counter
    // (Mana Leak), damage or anything else that declining lets happen
    private static boolean opponentsDraw(final Player ai, final SpellAbility sa) {
        if (sa.getApi() != ApiType.Draw || sa.usesTargeting() || sa.getSubAbility() != null
                || sa.hasParam("UnlessSwitched") || sa.getHostCard() == null) {
            return false;
        }
        final List<Player> drawers = AbilityUtils.getDefinedPlayers(sa.getHostCard(),
                sa.getParamOrDefault("Defined", "You"), sa);
        if (drawers.isEmpty()) {
            return false;
        }
        for (final Player p : drawers) {
            if (!p.isOpponentOf(ai)) {
                return false;
            }
        }
        return true;
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
        final ComboTracker.View v = t.view();
        if (window(ai, v, Emptier.PACT, sa, true)) {
            return Boolean.TRUE;
        }
        for (final ComboLines.Line l : v.live()) {
            if (l.emptier() == Emptier.PACT) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    /**
     * Whether we decline the card Tainted Pact just exiled (ChangeZoneAi.confirmAction): {@code sa} is Pact's
     * DBReturnToHand, an AbilitySub moving a card from exile to our hand, and an ORACLE, LAB or JACE window of a Pact
     * line holds now. Pact is resolving, so its own mana is paid: a following draw spell needs only its own. A LAB or
     * JACE window reads whether every instance on the stack is Pact's own (follow-up 4, I1): above an opponent's spell
     * beneath it only an instant-speed draw spell, cast right after Pact resolves, keeps the window open (follow-up 5,
     * J1: Opt declines the card; Serum Visions, or Opt under the opponent's Teferi, Time Raveler, keeps it, as stock
     * does).
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
        return window(ai, t.view(), Emptier.PACT, sa, false);
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
        final ComboTracker.View v = t.view();
        if (window(ai, v, Emptier.CONSULTATION, sa, true)) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return ComboFetch.consultTutor(ai, sa, v);
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
        for (final ComboLines.Line l : t.view().executable()) {
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
        return window(ai, t.view(), e, sa, true) ? "combo" : null;
    }

    /** Whether our next draw wins now, the go-off's last step (ComboHold's wheel, follow-up F5): our library is empty,
     *  we can win, and a Laboratory Maniac or Jace, Wielder of Mysteries of ours holds its win in play for a deck line,
     *  as {@link #finishesLine} reads them for a draw of ours. */
    static boolean nextDrawWins(final Player ai, final ComboTracker t) {
        return ai.getCardsIn(ZoneType.Library).isEmpty() && !ai.cantWin()
                && (inPlay(t, Winner.LAB) || inPlay(t, Winner.JACE));
    }

    // our own spell from our hand, not a copy and not a Play-effect cast, whose host is that emptier (ComboFetch's
    // Consultation tutor asks it too)
    static boolean ownHandCast(final Player ai, final SpellAbility sa, final Emptier e) {
        return ownHandCast(ai, sa, e.card);
    }

    // our own spell from our hand, not a copy and not a Play-effect cast, whose host is the card of that name
    private static boolean ownHandCast(final Player ai, final SpellAbility sa, final String card) {
        if (sa == null || !sa.isSpell() || sa.isCopied() || sa.isCastFromPlayEffect()) {
            return false;
        }
        final Card host = sa.getHostCard();
        if (host == null || !card.equals(ComboTracker.nameOf(host)) || !host.isInZone(ZoneType.Hand)
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

    // whether a window of a line of emptier e executable now (the view's executableNow) is open now. unpaid: the
    // emptier is still to be cast, so a following draw spell's mana comes on top of its own, its pips among it; false
    // while it resolves (paid already). The board's own part of a window is read first, over the deck's lines, before
    // any line's denial and signals: at most priority passes no winner of ours is in play and no trigger of ours is on
    // the stack, and no window opens. Each window opens now, so each reads the line's denial now: a Laboratory Maniac
    // line denied at its own window, the opponent's end step, still opens the draw spell's window in our main phase
    private static boolean window(final Player ai, final ComboTracker.View v, final Emptier e, final SpellAbility sa,
            final boolean unpaid) {
        boolean staged = false;
        for (final ComboLines.Line l : v.lines()) {
            if (l.emptier() == e && staged(v, l.winner())) {
                staged = true;
                break;
            }
        }
        if (!staged) {
            return false;
        }
        for (final ComboLines.Line l : v.executableNow()) {
            if (l.emptier() == e && staged(v, l.winner())
                    && (!drawsAfter(l.winner()) || drawFollows(ai, v, sa, unpaid))) {
                return true;
            }
        }
        return false;
    }

    // the winner's own part of its window, read from the board (an if chain, not an enum switch: javac would add a
    // synthetic switch-map class): Oracle's or Fblthp's trigger of ours on the stack; our Doctor Doom untapped and
    // free of summoning sickness; Laboratory Maniac or Jace on our battlefield, whose window also needs a draw of ours
    // to follow (drawsAfter, drawFollows). ComboFetch's win in time under danger reads Doom's for a win this turn
    static boolean staged(final ComboTracker.View v, final Winner w) {
        if (w == Winner.ORACLE || w == Winner.FBLTHP) {
            return v.triggerOnOurStack(w.card);
        }
        if (v.where(w.card) != ComboTracker.Where.BATTLEFIELD) {
            return false;
        }
        if (w == Winner.DOOM) {
            final Card doom = v.card(w.card);
            return !doom.isTapped() && !doom.isSick();
        }
        return true;
    }

    // whether the winner wins off a draw of ours after the emptier: Laboratory Maniac and Jace (ComboFetch's win in
    // time under danger asks it too)
    static boolean drawsAfter(final Winner w) {
        return w == Winner.LAB || w == Winner.JACE;
    }

    // a draw of ours follows the emptier, read by what the stack holds besides the emptier's own spell
    // (onlyTheEmptierOnTheStack, follow-up 4, I1; follow-up 5, J1). While it holds nothing else: the opponent's end
    // step with our turn next (our draw step, which no cast bar stops), or a spell in our hand that the AI casts right
    // after the emptier on the emptied library (followUpSpell), a sorcery-speed one only in our own main phase, where
    // the emptier leaves the stack empty, a sorcery's moment. Above anything else (the opponent's Swords to Plowshares
    // on our Laboratory Maniac): only such a spell that the AI casts at instant speed right after the emptier resolves,
    // above what remains, and that wins before it. Our draw step lies past what is beneath, and a sorcery-speed spell
    // waits for the empty stack, by when Swords has exiled Lab, so neither counts; and the draw's moment is no
    // sorcery's, so the opponent's Teferi, Time Raveler bars Opt there. The spell must be one the mana pays together
    // with the emptier still to be cast, each source assigned to one pip of the two costs (ComboMana): Consultation's
    // or Pact's {B} beside Opt's {U} (Review Focus 4: two Swamps pay the two mana values, never Opt; Command Tower and
    // Sol Ring make three mana, but one coloured), and one that can be cast there (follow-up 3, H3:
    // ComboSignals.drawBarredAt, at now's turn and step, at a sorcery's moment or not as above, after this turn's
    // spells and the emptier still to be cast: under Rule of Law or Deafening Silence, Opt after Consultation would be
    // the turn's second spell). Each cost is its spell's as the engine prices it now (priced, follow-up 2, G3: under an
    // opponent's Thalia, Guardian of Thraben, Consultation's {1}{B} and Opt's {1}{U})
    private static boolean drawFollows(final Player ai, final ComboTracker.View v, final SpellAbility sa,
            final boolean unpaid) {
        final boolean alone = onlyTheEmptierOnTheStack(ai, sa, unpaid);
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        if (alone && ph.is(PhaseType.END_OF_TURN) && ai.equals(ph.getNextTurn())) {
            return true;
        }
        ManaCost emptierCost = null;
        ComboMana mana = null;
        List<Card> before = null;
        // a sorcery's moment for the draw: our own main phase, nothing beneath the emptier
        final boolean sorcery = alone && ph.isPlayerTurn(ai) && ph.getPhase() != null && ph.getPhase().isMain();
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            final SpellAbility draw = c.isLand() ? null : followUpSpell(ai, sorcery, c);
            if (draw == null) {
                continue;
            }
            if (mana == null) {
                mana = ComboMana.of(ai, sa, true);
                emptierCost = unpaid ? priced(ai, sa) : ManaCost.ZERO;
                // the spells cast this turn before the draw: the turn's so far (a resolving Pact among them), then
                // the emptier still to be cast
                before = new ArrayList<>(ai.getGame().getStack().getSpellCardsCastThisTurn());
                if (unpaid) {
                    before.add(sa.getHostCard());
                }
            }
            if (mana.pays(emptierCost, priced(ai, draw))
                    && !ComboSignals.drawBarredAt(v, draw, ph.getPlayerTurn(), ph.getPhase(), sorcery, before)) {
                return true;
            }
        }
        return false;
    }

    // whether the stack holds nothing but the emptier's own spell (follow-up 4, I1), the Laboratory Maniac and Jace
    // windows' stack rule: on a cast (unpaid: Consultation's, Pact's, the floor's exemption) the stack is empty; while
    // Tainted Pact resolves (its decline at each exile) every instance on it is Pact's own spell, still the stack's
    // top (MagicStack.resolveStack removes the resolving spell only when it finishes). Anything else on it, an
    // opponent's Swords to Plowshares on our Laboratory Maniac above all, leaves the window only an instant-speed draw
    // spell cast right after the emptier resolves, above what remains (drawFollows, follow-up 5, J1): our draw step
    // lies past it, and a sorcery-speed draw could not follow until the stack is empty, by when Swords has exiled Lab
    // and the emptied library would deck us. Shut is the safe side: no emptier is cast, and at the AI's next priority,
    // the stack empty, the window opens if the line still stands
    private static boolean onlyTheEmptierOnTheStack(final Player ai, final SpellAbility sa, final boolean unpaid) {
        if (unpaid) {
            return ai.getGame().getStack().isEmpty();
        }
        final Card host = sa.getHostCard();
        for (final SpellAbilityStackInstance si : ai.getGame().getStack()) {
            if (!si.isSpell() || !host.equals(si.getSourceCard())) {
                return false;
            }
        }
        return true;
    }

    // the spells of our hand that the AI casts right after the emptier on the emptied library in our own main phase, as
    // drawFollows reads the hand there (lands left out, followUpSpell, each card's first such spell), that can be cast
    // there after before, the spells cast in that main phase's turn ahead of the draw, the emptier among them
    // (follow-up 3, H3: ComboSignals.drawBarredAt, in our Main 1 on an empty stack, a sorcery's moment): ComboFetch's
    // win in time under danger pays one of them, its cost as the engine prices it (priced, as drawFollows prices it),
    // beside a Laboratory Maniac or Jace line's casts, in the main phase of ours the fetched piece arrives for, this
    // turn's or our next turn's (follow-up 2, G4: a sorcery-speed draw too, though in our upkeep or at the opponent's
    // end step none could be cast now)
    static List<SpellAbility> followUpDraws(final ComboTracker.View v, final List<Card> before) {
        final Player ai = v.ai();
        final List<SpellAbility> out = new ArrayList<>(2);
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            final SpellAbility draw = c.isLand() ? null : followUpSpell(ai, true, c);
            if (draw != null && !ComboSignals.drawBarredAt(v, draw, ai, PhaseType.MAIN1, true, before)) {
                out.add(draw);
            }
        }
        return out;
    }

    // the first spell of the card that the AI casts right after the emptier on the emptied library (Review Focus 4),
    // else null:
    // - of the follow-up shape (followsUp), whose every part the AI approves on the emptied library;
    // - a sorcery-speed spell (SpellAbilityAi.isSorcerySpeed: no flash of its own or from a static) only at a sorcery's
    //   moment (sorcery: our own main phase, the emptier cast there with nothing beneath it, follow-up 5, J1);
    //   elsewhere it waits past our draw step, which the emptied library loses (Serum Visions in the opponent's Main 1,
    //   in our combat or at our own end step), or for the empty stack, by when what is beneath may have removed our
    //   winner (Serum Visions above the opponent's Swords to Plowshares on our Laboratory Maniac);
    // - a card the AI is never offered, never: getSpellAbilityToPlay drops a RemoveDeck-hinted card's spell unless its
    //   own row readmits it (SpecialCardAi.RemoveDeckFilter.readmit, asked as that filter asks it; See Beyond and
    //   Gitaxian Probe have none);
    // - a spell whose price asks the AI's controller or test-pays (pricingAsks), never: priced would ask it, and the
    //   convoke chooser draws (follow-up 3, H1: Meeting of Minds, Reverse Engineer, Treasure Cruise). Not counted, the
    //   draw starts no go-off, the safe side
    private static SpellAbility followUpSpell(final Player ai, final boolean sorcery, final Card c) {
        final boolean hinted = ComputerUtilCard.isCardRemAIDeck(c);
        for (final SpellAbility spell : c.getSpells()) {
            if (!followsUp(c, spell) || (!sorcery && SpellAbilityAi.isSorcerySpeed(spell, ai))
                    || pricingAsks(ai, spell)) {
                continue;
            }
            if (!hinted || SpecialCardAi.RemoveDeckFilter.readmit(ai, spell)) {
                return spell;
            }
        }
        return null;
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

    // whether pricing our spell (priced) would hand a step of its cost to the AI's controller, or test-pay it (follow-up
    // 3, H1: priced never does either). For a spell, CostAdjustment.adjust asks the PlayerController for:
    // - Convoke and Improvise (the host's keywords), TapCreaturesForMana (the spell's) and waterbend (a Waterbend part
    //   of the cost as CostAdjustment raises it, printed or added by a RaiseCost static: Water Whip's own), each
    //   through chooseCardsForConvokeOrImprovise, whose likely-blocker read (ComputerUtilCard.getLikelyBlockers)
    //   reaches MyRandom in the opponent's turn before blockers and in ours after combat begins;
    // - Delve, through chooseCardsToDelve (whose test mode also clears the host's delved cards);
    // - Assist, through choosePlayerToAssistPayment;
    // - Offering and Emerge, through choosePermanentsToSacrifice: creature casts only, never the follow-up shape, so not
    //   read here.
    // calculateManaCost test-pays an Announce$ NumTimes cost (canPayManaCost), which draws. Every spell's price under a
    // ReduceCost static also reaches chooseSingleStaticAbility, and under an UpTo one chooseNumberForCostReduction. The
    // AI answers them with the first static and the most, reading and drawing nothing, and they stay (Helm of
    // Awakening's reduction). The keywords and params are read first, then the raise, which draws nothing and asks
    // nobody (the commander tax and the RaiseCost statics: priced's own first step), with us as the spell's activator
    private static boolean pricingAsks(final Player ai, final SpellAbility spell) {
        final Card host = spell.getHostCard();
        if (host.hasKeyword(Keyword.CONVOKE) || host.hasKeyword(Keyword.IMPROVISE) || host.hasKeyword(Keyword.DELVE)
                || host.hasKeyword(Keyword.ASSIST) || spell.hasParam("TapCreaturesForMana")
                || "NumTimes".equals(spell.getParam("Announce"))) {
            return true;
        }
        spell.setActivatingPlayer(ai);
        return CostAdjustment.adjust(spell.getPayCosts(), spell, false).getMaxWaterbend() != null;
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
