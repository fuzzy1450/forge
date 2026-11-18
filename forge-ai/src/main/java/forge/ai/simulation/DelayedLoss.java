package forge.ai.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import forge.ai.AiCardMemory;
import forge.ai.ComputerUtilMana;
import forge.card.CardRules;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardLists;
import forge.game.card.CardTraitChanges;
import forge.game.card.CardUtil;
import forge.game.card.CounterEnumType;
import forge.game.card.ICardTraitChanges;
import forge.game.cost.Cost;
import forge.game.cost.CostDamage;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostExile;
import forge.game.cost.CostGainControl;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayLife;
import forge.game.cost.CostPutCardToLib;
import forge.game.cost.CostPutCounter;
import forge.game.cost.CostReturn;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTap;
import forge.game.cost.CostTapType;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementType;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityRestriction;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;
import forge.game.zone.ZoneType;
import forge.util.AITimeoutTracker;

/**
 * The AI's own delayed-loss cards, and the "pay or lose" debts they leave (MTG_DeckMaker's self-kill fix, its
 * DESIGN.md section 1). Three classes, recognized by script shape ({@link #kind}):
 * <ul>
 *   <li><b>(a) the five Pacts</b> (Pact of Negation, Summoner's Pact, Slaughter Pact, Intervention Pact, Pact of the
 *       Titan): cast only while the AI owes no other such debt, and only when its own debt can be paid at the AI's
 *       next upkeep from mana it will certainly have then, counted as that upkeep's payment counts it
 *       ({@link #count});</li>
 *   <li><b>(b) the extra turns that lose at their end step</b> (Final Fortune, Last Chance, Warrior's Oath, Chance for
 *       Glory, Alchemist's Gambit's normal cast) and <b>(c) Glorious End</b>: never cast. No search the engine has
 *       reaches an extra turn's end step, so "the extra turn wins" cannot be checked.</li>
 * </ul>
 * Every read here is a pure state read (DESIGN.md 1.10): no random number is drawn, no SimScope id is taken (no
 * SpellAbility, Trigger, Cost, Card, StaticAbility or ReplacementEffect is built), no iteration order changes, and the
 * only field written is the activator {@code ManaConversion.withActivator} stamps on a mana ability that has none, the
 * value ComboGuard's Pact check and the AI's own mana estimate stamp. A game whose decks list none of the eleven cards
 * reads only script parameters and two empty lists here.
 */
public final class DelayedLoss {
    private DelayedLoss() {
    }

    // ------------------------------------------------------------------------------------------------ codes (1.1)

    /** The Pact's debt is not covered at the next upkeep. ComboGuard's own code, so the rejection tallies compare. */
    public static final String PACT_UNPAYABLE = "pact-unpayable";
    /** Another pay-or-lose debt is already owed: one debt per upkeep (DESIGN.md 1.3, D23). */
    public static final String PACT_DEBT_PENDING = "pact-debt-pending";
    /** Class (b): an extra turn that ends in a loss. */
    public static final String EXTRA_TURN_LOSES = "extra-turn-loses";
    /** Class (c): a loss at an end step. */
    public static final String END_STEP_LOSES = "end-step-loses";
    /** A later play's cost would uncover a pending debt (DESIGN.md 1.7). */
    public static final String DEBT_UNPROTECTED = "debt-unprotected";
    /** While a debt is due now (a Pact's trigger on the stack), the play could leave it uncovered (DESIGN.md 1.7). */
    public static final String DEBT_DUE_NOW = "debt-due-now";

    // -------------------------------------------------------------------------------------------- constants (1.11)

    /** A life payment leaves at least this much: the payment's own floor (ComputerUtilMana.java:1987). */
    static final int PAY_LIFE_FLOOR = 1;
    /** A self-damage sub is refused by the payment when damage + this >= life (DamageDealAi.java:1050). */
    static final int DAMAGE_MARGIN = 3;
    /** With this few cards left in a library-out deck's library its turn is taken as won (from ComboGuard). */
    static final int PACT_LIBRARY_OUT_LEFT = 3;
    /** The life the AI pays for a Phyrexian shard no source of its pays (ComputerUtilMana.java:705, 795-832). */
    static final int PHYREXIAN_LIFE = 2;
    /**
     * How deep a script is followed: an extra turn's execute to its loss (Effect triggers, sub-abilities), and a play's
     * additional abilities under one another ({@link #parts}; no spell or activated ability of the cardsfolder nests
     * more than four).
     */
    static final int REACH_DEPTH = 4;

    /** The colors of a unit or a pip, as bits: MagicColor's five, and colorless. */
    static final int W = MagicColor.WHITE;
    static final int U = MagicColor.BLUE;
    static final int B = MagicColor.BLACK;
    static final int R = MagicColor.RED;
    static final int G = MagicColor.GREEN;
    static final int C = 1 << 5;
    static final int WUBRG = W | U | B | R | G;
    private static final String LETTERS = "WUBRG";
    private static final int[] LETTER_BITS = { W, U, B, R, G };

    private static final String DOESNT_UNTAP_NEXT = "This card doesn't untap during your next untap step.";
    private static final String DOESNT_UNTAP = "CARDNAME doesn't untap during your untap step.";
    private static final String RETURNS_AT_UNTAP =
            "During your next untap step, as you untap your permanents, return CARDNAME to its owner's hand.";

    // ------------------------------------------------------------------------------------------- recognition (1.2)

    /** The three delayed-loss classes. */
    public enum Kind { PACT, EXTRA_TURN, END_STEP }

    /** When a debt is due (DESIGN.md 1.3): at the caster's next upkeep, or as the trigger on the stack resolves. */
    public enum Due { NEXT, NOW }

    /**
     * The play's delayed-loss class, read from its script parameters only, or null.
     * <ul>
     *   <li>A {@code DelayedTrigger} whose {@code Execute$} is an own {@code LosesGame}: {@link Kind#PACT} with
     *       {@code Phase$ Upkeep} and an {@code UnlessCost$} on the loss (ComboGuard.pactCost's shape), else
     *       {@link Kind#END_STEP}.</li>
     *   <li>An {@code AddTurn} whose {@code ExtraTurnDelayedTriggerExecute$} reaches an own {@code LosesGame}:
     *       {@link Kind#EXTRA_TURN}.</li>
     * </ul>
     * The SVar text is split here, never through FileSection's JVM-wide cache or AbilityFactory, which builds.
     */
    public static Kind kind(final SpellAbility sa) {
        final Card host = sa == null ? null : sa.getHostCard();
        if (host == null) {
            return null;
        }
        for (SpellAbility a = sa; a != null; a = a.getSubAbility()) {
            final ApiType api = a.getApi();
            if (api == ApiType.DelayedTrigger) {
                final String ex = a.getParam("Execute");
                final Map<String, String> loss = ex == null ? Collections.emptyMap() : params(host.getSVar(ex));
                if (ownLoss(loss)) {
                    return "Upkeep".equals(a.getParam("Phase")) && loss.containsKey("UnlessCost") ? Kind.PACT
                            : Kind.END_STEP;
                }
            } else if (api == ApiType.AddTurn && a.hasParam("ExtraTurnDelayedTriggerExecute")
                    && reachesOwnLoss(host, a.getParam("ExtraTurnDelayedTriggerExecute"), REACH_DEPTH)) {
                return Kind.EXTRA_TURN;
            }
        }
        return null;
    }

    /** An SVar's text split into its parameters: on {@code |}, then at the first {@code $}. */
    static Map<String, String> params(final String text) {
        final Map<String, String> out = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (final String piece : text.split("\\|")) {
            final int at = piece.indexOf('$');
            if (at > 0) {
                out.put(piece.substring(0, at).trim(), piece.substring(at + 1).trim());
            }
        }
        return out;
    }

    /** The API word of split SVar text ({@code DB$}, {@code SP$} or {@code AB$}), or null. */
    private static String api(final Map<String, String> p) {
        final String db = p.get("DB");
        if (db != null) {
            return db;
        }
        final String sp = p.get("SP");
        return sp != null ? sp : p.get("AB");
    }

    /** A {@code LosesGame} of the caster itself: not targeted, {@code Defined$} absent, You or SourceController. */
    private static boolean ownLoss(final Map<String, String> p) {
        if (!"LosesGame".equals(api(p)) || p.containsKey("ValidTgts")) {
            return false;
        }
        final String defined = p.get("Defined");
        return defined == null || "You".equals(defined) || "SourceController".equals(defined);
    }

    /** Whether the SVar is an own loss, or reaches one through an Effect's {@code Triggers$} or a {@code SubAbility$}. */
    private static boolean reachesOwnLoss(final Card host, final String name, final int depth) {
        if (name == null || name.isEmpty() || depth <= 0) {
            return false;
        }
        final Map<String, String> p = params(host.getSVar(name));
        if (ownLoss(p)) {
            return true;
        }
        final String triggers = p.get("Triggers");
        if ("Effect".equals(api(p)) && triggers != null) {
            for (final String trig : triggers.split(",")) {
                if (reachesOwnLoss(host, params(host.getSVar(trig.trim())).get("Execute"), depth - 1)) {
                    return true;
                }
            }
        }
        return reachesOwnLoss(host, p.get("SubAbility"), depth - 1);
    }

    // ------------------------------------------------------------------------------------------------ debts (1.3)

    /** A mana debt: its total and its colored pips, each pip a mask of the colors that may pay it. */
    public static final class Debt {
        /** Nothing owed. */
        public static final Debt NONE = new Debt(0, Collections.emptyList());

        final int total;
        final List<Integer> pips;

        private Debt(final int total, final List<Integer> pips) {
            this.total = total;
            this.pips = Collections.unmodifiableList(pips);
        }

        /**
         * A cost as a script writes it ("3 U U": 5, with pips U U). A number is generic; a color letter is a pip of
         * that color; a hybrid token ("W/U") is one pip either color pays. Any other token is a pip nothing pays, and
         * no cost at all is a debt nothing covers: what cannot be read is not counted as payable.
         */
        public static Debt of(final String cost) {
            if (cost == null) {
                return new Debt(1, List.of(0));
            }
            int total = 0;
            final List<Integer> pips = new ArrayList<>();
            for (final String token : cost.trim().split("\\s+")) {
                if (token.isEmpty()) {
                    continue;
                }
                if (token.chars().allMatch(Character::isDigit)) {
                    try {
                        total += Integer.parseInt(token);
                    } catch (final NumberFormatException e) {
                        return new Debt(1, List.of(0));
                    }
                    continue;
                }
                int mask = 0;
                for (final String part : token.split("/")) {
                    mask |= letterBit(part);
                }
                total++;
                pips.add(mask & WUBRG);
            }
            return new Debt(total, pips);
        }

        /** The joint debt of several: totals and pips added up. */
        static Debt sum(final List<Debt> debts) {
            if (debts.isEmpty()) {
                return NONE;
            }
            if (debts.size() == 1) {
                return debts.get(0);
            }
            int total = 0;
            final List<Integer> pips = new ArrayList<>();
            for (final Debt d : debts) {
                total += d.total;
                pips.addAll(d.pips);
            }
            return new Debt(total, pips);
        }

        public int total() {
            return total;
        }

        public List<Integer> pips() {
            return pips;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder().append(total);
            for (final int pip : pips) {
                sb.append(' ').append(colors(pip));
            }
            return sb.toString();
        }
    }

    /** The debts the AI owes, each tagged with when it is due (DESIGN.md 1.3, round 2's B1). */
    public static final class Debts {
        static final Debts NONE = new Debts(Collections.emptyList(), Collections.emptyList());

        private final List<Debt> next;
        private final List<Debt> now;

        Debts(final List<Debt> next, final List<Debt> now) {
            this.next = Collections.unmodifiableList(next);
            this.now = Collections.unmodifiableList(now);
        }

        public boolean isEmpty() {
            return next.isEmpty() && now.isEmpty();
        }

        public boolean hasNow() {
            return !now.isEmpty();
        }

        public boolean hasNext() {
            return !next.isEmpty();
        }

        /** Every debt due now, as one. */
        public Debt now() {
            return Debt.sum(now);
        }

        /** Every debt due at the next upkeep, as one. */
        public Debt next() {
            return Debt.sum(next);
        }

        /** The debts due now, one by one. */
        public List<Debt> nowDebts() {
            return now;
        }

        /** The debts due at the next upkeep, one by one. */
        public List<Debt> nextDebts() {
            return next;
        }

        @Override
        public String toString() {
            return "NEXT" + next + " NOW" + now;
        }
    }

    /**
     * Every pay-or-lose debt the AI owes, but {@code judged}'s own:
     * <ul>
     *   <li>NEXT: an upkeep delayed trigger already registered (a Pact resolved this turn or an earlier one), whose
     *       overriding ability is the AI's {@code LosesGame} with an {@code UnlessCost$}; and a Pact of the AI's on
     *       the stack, not resolved yet;</li>
     *   <li>NOW: the AI's own {@code LosesGame} with an {@code UnlessCost$} on the stack, the Pact's trigger at the
     *       AI's upkeep (or a copy of it), read through its WrappedAbility.</li>
     * </ul>
     * Two scans of existing lists. {@code Trigger.ensureAbility} is never called: without an overriding ability it
     * would build one. An item that cannot be read is owed as a debt nothing covers.
     */
    public static Debts owed(final Player ai, final SpellAbility judged) {
        if (ai == null || ai.getGame() == null) {
            return Debts.NONE;
        }
        final Game game = ai.getGame();
        final List<Debt> next = new ArrayList<>();
        final List<Debt> now = new ArrayList<>();
        for (final Trigger t : game.getTriggerHandler().getDelayedTriggersView()) {
            try {
                if (t.getMode() != TriggerType.Phase || !"Upkeep".equals(t.getParam("Phase"))) {
                    continue;
                }
                final SpellAbility o = t.getOverridingAbility();
                if (o == null || o.getApi() != ApiType.LosesGame || !o.hasParam("UnlessCost")) {
                    continue;
                }
                Player owner = o.getActivatingPlayer();
                if (owner == null && t.getSpawningAbility() != null) {
                    owner = t.getSpawningAbility().getActivatingPlayer();
                }
                if (ai.equals(owner)) {
                    next.add(Debt.of(o.getParam("UnlessCost")));
                }
            } catch (final AITimeoutTracker.Expired e) {
                throw e;
            } catch (final RuntimeException e) {
                next.add(Debt.of(null));
            }
        }
        for (final SpellAbilityStackInstance si : game.getStack()) {
            try {
                final SpellAbility s = si.getSpellAbility();
                if (s == null || s == judged || !ai.equals(s.getActivatingPlayer())) {
                    continue;
                }
                final String pact = ComboGuard.pactCost(s);
                if (pact != null) {
                    next.add(Debt.of(pact));
                } else if (s.getApi() == ApiType.LosesGame && s.hasParam("UnlessCost")) {
                    now.add(Debt.of(s.getParam("UnlessCost")));
                }
            } catch (final AITimeoutTracker.Expired e) {
                throw e;
            } catch (final RuntimeException e) {
                now.add(Debt.of(null));
            }
        }
        return next.isEmpty() && now.isEmpty() ? Debts.NONE : new Debts(next, now);
    }

    // ---------------------------------------------------------------------------------------------- removal (1.7)

    /**
     * What a play's cost would take before a debt is paid (DESIGN.md 1.7): the cards it takes whatever the payment
     * chooses, the choices of each part that takes one card (the payment takes one of each group), and life.
     * {@link #count} leaves a removal's cards and life out; {@link #cases} spells out the payment's picks.
     */
    public static final class Removal {
        /** Nothing taken. */
        public static final Removal NONE = new Removal(Collections.emptyList(), 0);
        /** More ways to pay than this are not tried one by one: every relevant choice is taken at once (refuses more). */
        static final int MAX_CASES = 64;

        private final List<Card> cards;
        private final int life;
        private final boolean lifeUnread;
        private final List<List<Card>> oneOf;

        Removal(final List<Card> cards, final int life) {
            this(cards, life, false, Collections.emptyList());
        }

        private Removal(final List<Card> cards, final int life, final boolean lifeUnread,
                        final List<List<Card>> oneOf) {
            this.cards = Collections.unmodifiableList(new ArrayList<>(cards));
            this.life = life;
            this.lifeUnread = lifeUnread;
            this.oneOf = Collections.unmodifiableList(new ArrayList<>(oneOf));
        }

        /** Whether the cost takes nothing the count reads: no card, no choice of one, no life. */
        public boolean isEmpty() {
            return cards.isEmpty() && oneOf.isEmpty() && life == 0 && !lifeUnread;
        }

        /**
         * The parts of a cost that can take what a debt's count relies on (DESIGN.md 1.7, round 2's B1 and M5), by
         * when the debt is due:
         * <ul>
         *   <li>either: a sacrifice, a return, an exile or a put-into-library from the battlefield, or a gain of
         *       control (the AI's battlefield cards valid for the part's type; a part paid from its source takes the
         *       source), -1/-1 counters that kill a creature of the AI's (RULINGS-R3 M28), and a life payment or
         *       damage to the AI;</li>
         *   <li>{@link Due#NOW}: also a tap of the source, or of untapped permanents of a type: nothing untaps before
         *       the trigger resolves. A hand part is free then, no land drop being counted;</li>
         *   <li>{@link Due#NEXT}: also a discard, or an exile or a put-into-library from the hand, while the land drop
         *       is still to come, and only of lands: the drop is the hand's only unit. A tap is free: a counted source
         *       untaps.</li>
         * </ul>
         * A part that takes one card adds a group of its choices, one of which the payment takes; a part that takes
         * more, X or all takes every valid choice. A life amount that is not a number is noted. A part that cannot be
         * read takes every card of the AI's battlefield (and its hand's lands, due next upkeep). Reads only: script
         * parameters, zones and {@code CardLists.getValidCards}.
         */
        public static Removal of(final Player ai, final Cost cost, final SpellAbility judged, final Due due) {
            if (ai == null || cost == null) {
                return NONE;
            }
            final boolean now = due == Due.NOW;
            final Card host = judged == null ? null : judged.getHostCard();
            final List<Card> cards = new ArrayList<>();
            final List<List<Card>> oneOf = new ArrayList<>();
            int life = 0;
            boolean lifeUnread = false;
            Boolean dropToCome = null; // read once, for a hand part due next upkeep
            for (final CostPart part : cost.getCostParts()) {
                try {
                    if (part instanceof CostPayLife || part instanceof CostDamage) {
                        final Integer amount = part.convertAmount();
                        if (amount == null) {
                            lifeUnread = true;
                        } else {
                            life += Math.max(0, amount);
                        }
                    } else if (part instanceof CostTap) {
                        if (now && host != null && host.isInZone(ZoneType.Battlefield)
                                && ai.equals(host.getController())) {
                            cards.add(host);
                        }
                    } else if (part instanceof CostTapType) {
                        if (now) {
                            choose(ai, part, host, judged, ZoneType.Battlefield, cards, oneOf);
                        }
                    } else if (fromBattlefield(part)) {
                        choose(ai, part, host, judged, ZoneType.Battlefield, cards, oneOf);
                    } else if (part instanceof CostPutCounter && ((CostPutCounter) part).getCounter() != null
                            && ((CostPutCounter) part).getCounter().is(CounterEnumType.M1M1)) {
                        killed(ai, (CostPutCounter) part, host, judged, cards, oneOf);
                    } else if (!now && fromHand(part)) {
                        if (dropToCome == null) {
                            dropToCome = landMayBePlayed(ai);
                        }
                        if (dropToCome) {
                            choose(ai, part, host, judged, ZoneType.Hand, cards, oneOf);
                        }
                    }
                } catch (final AITimeoutTracker.Expired e) {
                    throw e;
                } catch (final RuntimeException e) {
                    cards.addAll(ai.getCardsIn(ZoneType.Battlefield)); // what cannot be read is not counted on
                    if (!now) {
                        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
                            if (c.isLand()) {
                                cards.add(c);
                            }
                        }
                    }
                }
            }
            return new Removal(cards, life, lifeUnread, oneOf);
        }

        private static boolean fromBattlefield(final CostPart part) {
            return part instanceof CostSacrifice || part instanceof CostReturn || part instanceof CostGainControl
                    || part instanceof CostExile && ((CostExile) part).getFrom().contains(ZoneType.Battlefield)
                    || part instanceof CostPutCardToLib
                    && ((CostPutCardToLib) part).getFrom() == ZoneType.Battlefield;
        }

        private static boolean fromHand(final CostPart part) {
            return part instanceof CostDiscard
                    || part instanceof CostExile && ((CostExile) part).getFrom().contains(ZoneType.Hand)
                    || part instanceof CostPutCardToLib && ((CostPutCardToLib) part).getFrom() == ZoneType.Hand;
        }

        /**
         * A part's choices among the AI's cards of a zone, the hand's lands only and a tap's untapped cards only: a
         * group when the part takes one card, else every choice.
         */
        private static void choose(final Player ai, final CostPart part, final Card host, final SpellAbility judged,
                                   final ZoneType zone, final List<Card> cards, final List<List<Card>> oneOf) {
            final List<Card> inZone = new ArrayList<>(ai.getCardsIn(zone));
            if (part.payCostFromSource()) {
                if (host != null && inZone.contains(host)) {
                    cards.add(host);
                }
                return;
            }
            final String type = part.getType().replace("+WithSameName", "").replace("+WithDifferentNames", "");
            final List<Card> valid;
            if ("Hand".equals(type) || "Random".equals(type) || type.contains("X")) {
                valid = inZone;
            } else if ("LastDrawn".equals(type)) {
                final Card last = ai.getLastDrawnCard();
                valid = last != null && inZone.contains(last) ? List.of(last) : List.of();
            } else {
                valid = CardLists.getValidCards(inZone, type.split(";"), ai, host, judged);
            }
            final List<Card> choices = new ArrayList<>();
            for (final Card c : valid) {
                if (zone == ZoneType.Hand ? c.isLand() : !(part instanceof CostTapType) || !c.isTapped()) {
                    choices.add(c);
                }
            }
            final Integer n = part.convertAmount();
            if ("Hand".equals(type) || n == null || n > 1) {
                cards.addAll(choices);
            } else if (n == 1 && !choices.isEmpty()) {
                oneOf.add(choices);
            }
        }

        /**
         * RULINGS-R3 M28: -1/-1 counters put as a cost kill a creature of the AI's whose toughness is at most their
         * number (Devoted Druid's untap): the source itself, or, a group, the creatures of the part's type that die
         * of them. An amount that is not a number kills whatever it may go on.
         */
        private static void killed(final Player ai, final CostPutCounter part, final Card host,
                                   final SpellAbility judged, final List<Card> cards, final List<List<Card>> oneOf) {
            final Integer n = part.convertAmount();
            final List<Card> candidates = new ArrayList<>();
            if (!part.payCostFromSource()) {
                candidates.addAll(CardLists.getValidCards(ai.getCardsIn(ZoneType.Battlefield),
                        part.getType().split(";"), ai, host, judged));
            } else if (host != null) {
                candidates.add(host);
            }
            final List<Card> dies = new ArrayList<>();
            for (final Card c : candidates) {
                if (c.isCreature() && c.isInZone(ZoneType.Battlefield) && ai.equals(c.getController())
                        && (n == null || c.getNetToughness() <= n)) {
                    dies.add(c);
                }
            }
            if (part.payCostFromSource() || n == null) {
                cards.addAll(dies);
            } else if (!dies.isEmpty()) {
                oneOf.add(dies);
            }
        }

        /**
         * Each way the payment can take what the count relies on: the cards taken whatever it chooses, with one
         * relevant choice of each group. A group with no relevant choice takes nothing the count reads. With more
         * than {@link #MAX_CASES} ways, one case takes every relevant choice at once.
         */
        List<Removal> cases(final Predicate<Card> relevant) {
            final List<List<Card>> groups = new ArrayList<>();
            int ways = 1;
            for (final List<Card> group : oneOf) {
                final List<Card> picks = new ArrayList<>();
                for (final Card c : group) {
                    if (relevant.test(c) && !picks.contains(c)) {
                        picks.add(c);
                    }
                }
                if (!picks.isEmpty()) {
                    groups.add(picks);
                    ways = ways > MAX_CASES ? ways : ways * picks.size();
                }
            }
            List<List<Card>> taken = new ArrayList<>();
            taken.add(new ArrayList<>(cards));
            if (ways > MAX_CASES) {
                for (final List<Card> group : groups) {
                    taken.get(0).addAll(group);
                }
            } else {
                for (final List<Card> group : groups) {
                    final List<List<Card>> next = new ArrayList<>();
                    for (final List<Card> sofar : taken) {
                        for (final Card c : group) {
                            final List<Card> one = new ArrayList<>(sofar);
                            one.add(c);
                            next.add(one);
                        }
                    }
                    taken = next;
                }
            }
            final List<Removal> out = new ArrayList<>();
            for (final List<Card> t : taken) {
                out.add(new Removal(t, life, lifeUnread, Collections.emptyList()));
            }
            return out;
        }

        /** Whether a life amount of the cost cannot be read as a number. */
        boolean lifeUnread() {
            return lifeUnread;
        }

        /** This removal with more cards taken and more life paid. */
        Removal plus(final List<Card> more, final int moreLife) {
            final List<Card> all = new ArrayList<>(cards);
            all.addAll(more);
            return new Removal(all, life + moreLife, lifeUnread, oneOf);
        }

        /** Whether the card is among those taken (identity: the cards of this game). */
        boolean takes(final Card c) {
            for (final Card x : cards) {
                if (x == c) {
                    return true;
                }
            }
            return false;
        }

        List<Card> cards() {
            return cards;
        }

        int life() {
            return life;
        }
    }

    // ------------------------------------------------------------------------------------------ the count (1.4-1.6)

    /** One way a source can tap: its units' colors, its pain by kind, and a filter's input. */
    static final class Option {
        final int[] units;
        final int payLife;
        final int damage;
        final int tapPain;
        final int filterInput;

        Option(final int[] units, final int payLife, final int damage, final int tapPain, final int filterInput) {
            this.units = units;
            this.payLife = payLife;
            this.damage = damage;
            this.tapPain = tapPain;
            this.filterInput = filterInput;
        }

        int pain() {
            return payLife + damage + tapPain;
        }

        int colors() {
            int mask = 0;
            for (final int u : units) {
                mask |= u;
            }
            return mask;
        }

        /** Whether the pain of each kind is within the payment's own margin, against the life budget. */
        boolean fits(final int budget) {
            return (payLife == 0 || budget - payLife >= PAY_LIFE_FLOOR)
                    && (damage == 0 || damage + DAMAGE_MARGIN < budget)
                    && (tapPain == 0 || tapPain <= budget - PAY_LIFE_FLOOR);
        }
    }

    /** A counted source: its best painless option, and its option with pain when that one gives more. */
    static final class Source {
        final Card card;
        final Option painless;
        final Option painful;
        Option counted;

        Source(final Card card, final Option painless, final Option painful) {
            this.card = card;
            this.painless = painless;
            this.painful = painful;
            this.counted = painless;
        }
    }

    /**
     * The mana a count found: for each counted source the option it is counted with (one ability per source: a source
     * taps once), the land drop's unit, and the counted filters' inputs.
     */
    public static final class Mana {
        static final Mana NONE = new Mana(Collections.emptyList(), 0, false);

        final List<Source> sources;
        final int landDrop;
        final boolean painlessOnly;

        Mana(final List<Source> sources, final int landDrop, final boolean painlessOnly) {
            this.sources = Collections.unmodifiableList(sources);
            this.landDrop = landDrop;
            this.painlessOnly = painlessOnly;
        }

        private Option option(final Source s) {
            return painlessOnly ? s.painless : s.counted;
        }

        /** The units, as color masks, in count order: the sources', then the land drop's. */
        public List<Integer> units() {
            final List<Integer> out = new ArrayList<>();
            for (final Source s : sources) {
                final Option o = option(s);
                if (o != null) {
                    for (final int u : o.units) {
                        out.add(u);
                    }
                }
            }
            if (landDrop != 0) {
                out.add(landDrop);
            }
            return out;
        }

        /** The generic mana the counted filters (Signets) need besides the debt. */
        public int filterInput() {
            int input = 0;
            for (final Source s : sources) {
                final Option o = option(s);
                if (o != null) {
                    input += o.filterInput;
                }
            }
            return input;
        }

        /** The number of counted filters. */
        public int filters() {
            int n = 0;
            for (final Source s : sources) {
                final Option o = option(s);
                if (o != null && o.filterInput > 0) {
                    n++;
                }
            }
            return n;
        }

        /** Whether the debt is covered (DESIGN.md 1.6): units for its total and the filters' inputs, and a distinct
         *  unit of a fitting color for each pip (a bipartite matching). */
        public boolean pays(final Debt debt) {
            if (debt == null) {
                return true;
            }
            final List<Integer> units = units();
            if (units.size() < debt.total + filterInput()) {
                return false;
            }
            return matches(debt.pips, units);
        }

        /** The same sources counted without pain: each with its painless option, a source with none left out. */
        public Mana painless() {
            return painlessOnly ? this : new Mana(sources, landDrop, true);
        }

        /**
         * DESIGN.md 1.7, a mana payment while a debt is due now: whether the debt stays covered whichever of these
         * sources a payment of mana value {@code t} taps. Each counted source makes at least one mana, so the payment
         * taps at most t of them, and one more for each filter it uses (a Signet's {1} comes from another source): k =
         * t + min(t, the counted filters). Two worst cases decide it for a one-color debt, and each is tested for the
         * whole coverage: the k sources with the most units taken off, and, for each color the pips ask, the k with
         * the most units of that color. Mana a tapped source makes beyond the payment's need is not counted.
         */
        public boolean paysAfterAnyTapped(final Debt debt, final int t) {
            if (debt == null) {
                return true;
            }
            if (t <= 0) {
                return pays(debt);
            }
            final int k = t + Math.min(t, filters());
            if (!without(most(k, WUBRG | C)).pays(debt)) {
                return false;
            }
            final List<Integer> asked = new ArrayList<>();
            for (final int pip : debt.pips) {
                if (pip != 0 && !asked.contains(pip)) {
                    asked.add(pip);
                }
            }
            for (final int mask : asked) {
                if (!without(most(k, mask)).pays(debt)) {
                    return false;
                }
            }
            return true;
        }

        /** The k counted sources with the most units of these colors; ties by more units in all, then the card id. */
        private List<Source> most(final int k, final int mask) {
            final List<Source> counted = new ArrayList<>();
            for (final Source s : sources) {
                if (option(s) != null) {
                    counted.add(s);
                }
            }
            counted.sort(Comparator.comparingInt((Source s) -> -unitsOf(option(s), mask))
                    .thenComparingInt(s -> -option(s).units.length).thenComparingInt(s -> s.card.getId()));
            return counted.subList(0, Math.min(k, counted.size()));
        }

        private static int unitsOf(final Option o, final int mask) {
            int n = 0;
            for (final int u : o.units) {
                n += (u & mask) != 0 ? 1 : 0;
            }
            return n;
        }

        /** The same count with these sources tapped for another payment. */
        private Mana without(final List<Source> tapped) {
            final List<Source> left = new ArrayList<>();
            for (final Source s : sources) {
                if (!tapped.contains(s)) {
                    left.add(s);
                }
            }
            return new Mana(left, landDrop, painlessOnly);
        }

        /**
         * The counted units source by source, for the trace (DelayedLossTrace): each source's name and its units'
         * colors ({@code Island=U;Sol Ring=C+C}), its pain after a {@code ~} ({@code Ancient Tomb=C+C~2}), the land
         * drop as {@code drop=R}. Field reads only.
         */
        String describe() {
            final StringBuilder sb = new StringBuilder();
            for (final Source s : sources) {
                final Option o = option(s);
                if (o == null) {
                    continue;
                }
                sb.append(sb.length() == 0 ? "" : ";").append(s.card.getName()).append('=');
                for (int i = 0; i < o.units.length; i++) {
                    sb.append(i == 0 ? "" : "+").append(colors(o.units[i]));
                }
                if (o.pain() > 0) {
                    sb.append('~').append(o.pain());
                }
            }
            if (landDrop != 0) {
                sb.append(sb.length() == 0 ? "" : ";").append("drop=").append(colors(landDrop));
            }
            return sb.toString();
        }

        /** The counted sources' cards, in count order. */
        public List<Card> cards() {
            final List<Card> out = new ArrayList<>();
            for (final Source s : sources) {
                if (option(s) != null) {
                    out.add(s.card);
                }
            }
            return out;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            for (final Source s : sources) {
                final Option o = option(s);
                if (o == null) {
                    continue;
                }
                sb.append(sb.length() == 0 ? "" : ", ").append(s.card.getName()).append(':');
                for (final int u : o.units) {
                    sb.append(colors(u)).append(' ');
                }
                if (o.pain() > 0) {
                    sb.append("pain ").append(o.pain()).append(' ');
                }
                sb.setLength(sb.length() - 1);
            }
            if (landDrop != 0) {
                sb.append(sb.length() == 0 ? "" : ", ").append("land drop:").append(colors(landDrop));
            }
            return sb.toString();
        }
    }

    /** Kuhn's augmenting paths: whether every pip gets a distinct unit sharing one of its colors. */
    static boolean matches(final List<Integer> pips, final List<Integer> units) {
        final int[] owner = new int[units.size()];
        Arrays.fill(owner, -1);
        for (int p = 0; p < pips.size(); p++) {
            if (!augment(p, pips, units, owner, new boolean[units.size()])) {
                return false;
            }
        }
        return true;
    }

    private static boolean augment(final int p, final List<Integer> pips, final List<Integer> units, final int[] owner,
                                   final boolean[] seen) {
        for (int u = 0; u < units.size(); u++) {
            if (seen[u] || (pips.get(p) & units.get(u)) == 0) {
                continue;
            }
            seen[u] = true;
            if (owner[u] < 0 || augment(owner[u], pips, units, owner, seen)) {
                owner[u] = p;
                return true;
            }
        }
        return false;
    }

    /**
     * The mana the AI will certainly have when the debt is paid (DESIGN.md 1.4-1.6, with RULINGS-R3's B5, M20, M22,
     * M27 and M29(c)), the removal's cards and life left out. Only renewable mana its own upkeep payment would use:
     * <ul>
     *   <li>the base is {@code ComputerUtilMana.getAIPlayableMana}; an ability counts only with cost parts that are a
     *       tap, a life payment or a filter's mana, no restricted mana on any part of its chain (B5), none of the
     *       timing words or limits the upkeep's own {@code canPlay()} refuses (M9), and its zone and state
     *       restrictions met on today's board (M22); Mox Opal's metalcraft only from artifacts that stay;</li>
     *   <li>{@link Due#NEXT}: a source that untaps for certain (the read of 1.5, which builds nothing), the land drop
     *       still to come, and the pain budget {@code life - threat};</li>
     *   <li>{@link Due#NOW}: a source untapped now, but no summoning-sick creature's tap (M20), no land drop, the pain
     *       budget {@code life}, and per-turn limits refused too;</li>
     *   <li>painless abilities first, then painful ones in (pain, card id) order, each while it fits its kind's margin
     *       and the total fits; one ability per source, the one with the most units, one-unit abilities pooling their
     *       colors; a unit with no color is dropped.</li>
     * </ul>
     * Total: a source whose reads throw is not counted.
     */
    public static Mana count(final Player ai, final Due due, final Removal removal) {
        return count(ai, due, removal, null);
    }

    /**
     * {@link #count}, for a judgment the trace watches ({@code seen} not null: DelayedLossTrace): the threat it is made
     * with is noted, and a threat-free {@code seen} makes it with no threat term, the trace's second count of a refusal
     * (DESIGN.md 6.4, round 2's M4). Null, the count is {@link #count}'s.
     */
    static Mana count(final Player ai, final Due due, final Removal removal, final Seen seen) {
        if (ai == null || ai.getGame() == null) {
            return Mana.NONE;
        }
        final Removal taken = removal == null ? Removal.NONE : removal;
        final boolean now = due == Due.NOW;
        final Restrictors stops;
        try {
            stops = now ? Restrictors.NONE : Restrictors.read(ai);
        } catch (final AITimeoutTracker.Expired e) {
            throw e;
        } catch (final RuntimeException e) {
            return Mana.NONE; // an unreadable restriction: nothing is certain to untap
        }
        if (stops.all) {
            return Mana.NONE;
        }
        int threat;
        try {
            threat = now || seen != null && seen.threatFree ? 0 : threat(ai);
        } catch (final AITimeoutTracker.Expired e) {
            throw e;
        } catch (final RuntimeException e) {
            threat = ai.getLife(); // an unreadable threat leaves no life for pain
        }
        if (seen != null) {
            seen.threat = threat;
        }
        final int budget = ai.getLife() - taken.life() - threat;
        Boolean landPlay;
        try {
            landPlay = landMayBePlayed(ai);
        } catch (final AITimeoutTracker.Expired e) {
            throw e;
        } catch (final RuntimeException e) {
            landPlay = null; // unreadable: City of Traitors may go, and no land drop is counted
        }
        final boolean landMayBePlayed = landPlay == null || landPlay;
        final Lasting lasting = new Lasting(ai, taken);
        final List<Source> sources = new ArrayList<>();
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (taken.takes(c)) {
                continue;
            }
            try {
                final Source s = source(ai, c, now, stops, lasting, landMayBePlayed);
                if (s != null) {
                    sources.add(s);
                }
            } catch (final AITimeoutTracker.Expired e) {
                throw e;
            } catch (final RuntimeException e) {
                // an unreadable source is not counted (DESIGN.md 1.11: every method is total)
            }
        }
        fitPain(sources, budget);
        int drop = 0;
        if (!now && landPlay != null && landPlay) {
            try {
                drop = landDrop(ai, taken);
            } catch (final AITimeoutTracker.Expired e) {
                throw e;
            } catch (final RuntimeException e) {
                drop = 0;
            }
        }
        return new Mana(sources, drop, false);
    }

    /** The AI's own turn, before its end step, with a land play left. */
    private static boolean landMayBePlayed(final Player ai) {
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        final PhaseType phase = ph.getPhase();
        return ph.isPlayerTurn(ai) && phase != null && phase.isBefore(PhaseType.END_OF_TURN)
                && ai.getLandsPlayedThisTurn() < ai.getMaxLandPlays();
    }

    /**
     * The opponents' creature power while a combat of theirs can still come before the AI's next upkeep: on the AI's
     * own turn, on an opponent's turn before its second main phase, and always with more than one opponent (another's
     * turn may still come). A double striker counts twice its power (M27).
     */
    static int threat(final Player ai) {
        final PhaseHandler ph = ai.getGame().getPhaseHandler();
        final PhaseType phase = ph.getPhase();
        final boolean combatToCome = ph.isPlayerTurn(ai) || phase == null || phase.isBefore(PhaseType.MAIN2)
                || ai.getOpponents().size() > 1;
        if (!combatToCome) {
            return 0;
        }
        int threat = 0;
        for (final Player o : ai.getOpponents()) {
            for (final Card c : o.getCardsIn(ZoneType.Battlefield)) {
                if (c.isCreature()) {
                    threat += Math.max(0, c.getNetPower()) * (c.hasDoubleStrike() ? 2 : 1);
                }
            }
        }
        return threat;
    }

    /** Painful options in (pain, card id) order, each counted while its kind's margin and the total fit. */
    private static void fitPain(final List<Source> sources, final int budget) {
        final List<Source> painful = new ArrayList<>();
        for (final Source s : sources) {
            if (s.painful != null) {
                painful.add(s);
            }
        }
        painful.sort(Comparator.comparingInt((Source s) -> s.painful.pain()).thenComparingInt(s -> s.card.getId()));
        int spent = 0;
        for (final Source s : painful) {
            final Option o = s.painful;
            if (o.fits(budget) && spent + o.pain() <= budget - PAY_LIFE_FLOOR) {
                s.counted = o;
                spent += o.pain();
            }
        }
    }

    /** Mox Opal's count of lasting artifacts, read the first time an ability with metalcraft asks for it. */
    private static final class Lasting {
        private final Player ai;
        private final Removal taken;
        private int count = -1;

        Lasting(final Player ai, final Removal taken) {
            this.ai = ai;
            this.taken = taken;
        }

        int get() {
            if (count < 0) {
                count = lastingArtifacts(ai, taken);
            }
            return count;
        }
    }

    /**
     * One battlefield card as a source, or null when it gives nothing certain. For a debt due now the "will it untap"
     * read is not asked, a tapped source does not untap before the payment, but Mana Vault and its kind still count
     * nothing: the rule keeps one reading of that row (DESIGN.md 1.4, "A debt due now").
     */
    private static Source source(final Player ai, final Card c, final boolean now, final Restrictors stops,
                                 final Lasting lasting, final boolean landMayBePlayed) {
        if (c.isPhasedOut() || c.hasSVar("EndOfTurnLeavePlay")) {
            return null; // gone first
        }
        if (now ? c.isTapped() || printedSelfRestriction(c) : !willUntap(ai, c, stops)) {
            return null;
        }
        if (landMayBePlayed && sacrificedByLandPlay(c)) {
            return null; // City of Traitors while a land may still be played this turn
        }
        final int tapPain = tapTriggerPain(c);
        if (tapPain < 0) {
            return null;
        }
        final List<Option> options = new ArrayList<>();
        for (final SpellAbility ma : ComputerUtilMana.getAIPlayableMana(c)) {
            final Option o = option(ai, c, ma, now, lasting, tapPain);
            if (o != null && o.units.length > 0) {
                options.add(o);
            }
        }
        final List<Option> painless = new ArrayList<>();
        for (final Option o : options) {
            if (o.pain() == 0) {
                painless.add(o);
            }
        }
        final Option bestPainless = best(painless);
        final Option bestAll = best(options);
        final Option painful = bestAll != null && bestAll.pain() > 0 && better(bestAll, bestPainless) ? bestAll : null;
        return bestPainless == null && painful == null ? null : new Source(c, bestPainless, painful);
    }

    /**
     * The option a source taps for: the one with the most units, or, when each gives one unit, one unit of all their
     * colors (a Talisman's {C} or {U}{R}). A pooled unit carries the largest pain of each kind among those it pools.
     */
    static Option best(final List<Option> options) {
        if (options.isEmpty()) {
            return null;
        }
        Option most = null;
        for (final Option o : options) {
            if (most == null || o.units.length > most.units.length) {
                most = o;
            }
        }
        if (most.units.length > 1) {
            return most;
        }
        int mask = 0;
        int payLife = 0;
        int damage = 0;
        int tapPain = 0;
        int filterInput = 0;
        for (final Option o : options) {
            mask |= o.units[0];
            payLife = Math.max(payLife, o.payLife);
            damage = Math.max(damage, o.damage);
            tapPain = Math.max(tapPain, o.tapPain);
            filterInput = Math.max(filterInput, o.filterInput);
        }
        return new Option(new int[] { mask }, payLife, damage, tapPain, filterInput);
    }

    /** Whether the option with pain gives more than the painless one: more units, or as many of more colors. */
    private static boolean better(final Option withPain, final Option painless) {
        if (painless == null) {
            return true;
        }
        if (withPain.units.length != painless.units.length) {
            return withPain.units.length > painless.units.length;
        }
        final int a = withPain.colors();
        final int b = painless.colors();
        return a != b && (a & b) == b;
    }

    /** One mana ability as an option, or null when the upkeep's payment cannot count on it. */
    private static Option option(final Player ai, final Card c, final SpellAbility ma, final boolean now,
                                 final Lasting lasting, final int tapPain) {
        final Cost cost = ma.getPayCosts();
        boolean taps = false;
        int payLife = 0;
        int filterInput = 0;
        if (cost != null) {
            for (final CostPart part : cost.getCostParts()) {
                if (part instanceof CostTap) {
                    taps = true;
                } else if (part instanceof CostPayLife) {
                    payLife += Math.max(0, part.getAbilityAmount(ma));
                } else if (part instanceof CostPartMana) {
                    final ManaCost input = ((CostPartMana) part).getMana();
                    if (input == null || input.countX() > 0 || !input.isPureGeneric()) {
                        return null; // a filter paid with colored mana, or an X: not counted
                    }
                    filterInput += input.getGenericCost();
                } else {
                    return null; // sacrifice, discard, exile, tap another, counters: used up or not renewable
                }
            }
        }
        if (restrictedMana(ma)) {
            return null; // B5: spend-only-on mana never pays the debt
        }
        final SpellAbilityRestriction r = ma.getRestrictions();
        if (r != null && cannotActivateAtUpkeep(r, now)) {
            return null;
        }
        if (ma.isSuppressed() || c.isDetained()) {
            return null;
        }
        if (now && taps && c.isSick()) {
            return null; // M20: a creature that came under the AI's control this turn cannot tap for a debt due now
        }
        ManaConversion.withActivator(ma);
        if (r != null && (!r.checkZoneRestrictions(c, ma) || !r.checkOtherRestrictions(c, ma, ai)
                || r.isMetalcraft() && lasting.get() < 3)) {
            return null; // M22: zone and state restrictions on today's board; Opal's metalcraft from lasting artifacts
        }
        final List<Integer> units = new ArrayList<>();
        if (ma.getApi() == ApiType.ManaReflected) {
            final int mask = reflected(ai, ma);
            if (mask != 0) {
                units.add(mask);
            }
        } else {
            for (SpellAbility p = ma; p != null; p = p.getSubAbility()) {
                final AbilityManaPart mp = p.getManaPart();
                if (mp != null && p.metConditions()) {
                    addUnits(c, p, mp, units);
                }
            }
        }
        final int damage = selfDamage(ma);
        final int[] out = new int[units.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = units.get(i);
        }
        return new Option(out, payLife, damage, taps ? tapPain : 0, filterInput);
    }

    /** The life a mana ability's own sub-abilities cost its controller: a DealDamage or LoseLife with Defined$ You. */
    private static int selfDamage(final SpellAbility ma) {
        int damage = 0;
        for (SpellAbility p = ma; p != null; p = p.getSubAbility()) {
            if (p.getManaPart() != null || !"You".equals(p.getParam("Defined"))) {
                continue;
            }
            if (p.getApi() == ApiType.DealDamage && p.hasParam("NumDmg")) {
                damage += Math.max(0, AbilityUtils.calculateAmount(p.getHostCard(), p.getParam("NumDmg"), p));
            } else if (p.getApi() == ApiType.LoseLife && p.hasParam("LifeAmount")) {
                damage += Math.max(0, AbilityUtils.calculateAmount(p.getHostCard(), p.getParam("LifeAmount"), p));
            }
        }
        return damage;
    }

    /** B5: a part of the ability's chain makes mana with a spending restriction. */
    private static boolean restrictedMana(final SpellAbility ma) {
        for (SpellAbility p = ma; p != null; p = p.getSubAbility()) {
            final AbilityManaPart mp = p.getManaPart();
            if (mp != null && (notEmpty(mp.getManaRestrictions()) || notEmpty(mp.getExtraManaRestriction()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean notEmpty(final String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * M9: the timing words and limits the upkeep payment's {@code canPlay()} refuses, read as fields for the debt's due
     * time (the AI's upkeep), never for the moment of the read: sorcery speed, the opponent's turn, instant speed only,
     * a phase list without the upkeep, first combat or after blockers only, a per-game limit, and for a debt due now a
     * per-turn limit too. The AI's own turn ({@code PlayerTurn$}) holds at its upkeep. For a debt due next upkeep, a
     * condition on this turn ({@code IsPresent$ Card.Self+ThisTurnEntered}: Mirrex, the five Dark Fortress lands) is
     * one today's board can hold and the upkeep's cannot, so it drops the ability too (RULINGS-R3 M22; T1 review
     * finding 9). The Dark Fortress lands' other alternative, a basic land, is dropped with it: that only refuses more.
     */
    private static boolean cannotActivateAtUpkeep(final SpellAbilityRestriction r, final boolean now) {
        return r.isSorcerySpeed() || r.isOpponentTurn() || r.isInstantSpeed()
                || !r.getPhases().isEmpty() && !r.getPhases().contains(PhaseType.UPKEEP)
                || r.getFirstCombatOnly() || r.getAfterBlockersOnly() || r.getGameLimitToCheck() != null
                || now && r.getLimitToCheck() != null
                || !now && r.getIsPresent() != null && r.getIsPresent().contains("ThisTurn");
    }

    /** The units one mana part makes: Bloom Tender's shape one per own color, any color, a combination, or tokens. */
    private static void addUnits(final Card c, final SpellAbility p, final AbilityManaPart mp, final List<Integer> units) {
        if (mp.isSpecialMana()) {
            // never ManaEffect.handleSpecialMana: it writes the ability's express choice
            if (mp.getOrigProduced().startsWith("Special EachColorAmong_Valid")) {
                final ColorSet own = c.getColor();
                for (final int bit : LETTER_BITS) {
                    if (own.hasAnyColor(bit)) {
                        units.add(bit);
                    }
                }
            }
            return;
        }
        final int amount = p.hasParam("Amount") ? AbilityUtils.calculateAmount(p.getHostCard(), p.getParam("Amount"), p) : 1;
        if (amount <= 0) {
            return; // 0 counts 0: no max(1, ...)
        }
        if (mp.isAnyMana()) {
            for (int i = 0; i < amount; i++) {
                units.add(WUBRG);
            }
            return;
        }
        if (mp.isComboMana()) {
            final int mask = partColors(p, mp);
            if (mask != 0) {
                for (int i = 0; i < amount; i++) {
                    units.add(mask);
                }
            }
            return;
        }
        final String[] tokens = mp.mana(p).trim().split("\\s+");
        for (int i = 0; i < amount; i++) {
            for (final String token : tokens) {
                final int bit = letterBit(token);
                if (bit != 0) {
                    units.add(bit);
                }
            }
        }
    }

    /** What colors (and colorless) one mana part can make, Special mana left out: its own answer would write. */
    private static int partColors(final SpellAbility p, final AbilityManaPart mp) {
        if (mp.isSpecialMana()) {
            return 0;
        }
        if (mp.isAnyMana()) {
            return WUBRG;
        }
        int mask = 0;
        for (int i = 0; i < LETTERS.length(); i++) {
            if (mp.canProduce(String.valueOf(LETTERS.charAt(i)), p)) {
                mask |= LETTER_BITS[i];
            }
        }
        if (mp.canProduce("C", p)) {
            mask |= C;
        }
        return mask;
    }

    /**
     * A reflected ability's colors. {@code ReflectProperty$ Is} (Mox Amber, Chrome Mox) through
     * {@code CardUtil.getReflectableManaColors}; {@code Produce} (Exotic Orchard, Fellwar Stone, Reflecting Pool) as the
     * union of what the valid lands' own mana abilities can make, never through that helper, whose Produce branch
     * builds trigger abilities. A reflected ability of those lands is not followed.
     */
    private static int reflected(final Player ai, final SpellAbility ma) {
        final String property = ma.getParam("ReflectProperty");
        int mask = 0;
        if ("Is".equals(property)) {
            for (final String color : CardUtil.getReflectableManaColors(ma)) {
                mask |= MagicColor.fromName(color);
            }
            return mask & WUBRG;
        }
        if (!"Produce".equals(property) || !ma.hasParam("Valid") || ma.getParam("Valid").startsWith("Defined.")) {
            return 0;
        }
        for (final Card land : CardLists.getValidCards(ai.getGame().getCardsIn(ZoneType.Battlefield),
                ma.getParam("Valid"), ai, ma.getHostCard(), ma)) {
            for (final SpellAbility ab : land.getManaAbilities()) {
                if (ab.getApi() == ApiType.ManaReflected) {
                    continue;
                }
                ManaConversion.withActivator(ab); // as the engine's own Produce branch stamps it (CardUtil.java:331)
                for (SpellAbility p = ab; p != null; p = p.getSubAbility()) {
                    final AbilityManaPart mp = p.getManaPart();
                    if (mp != null && p.metConditions()) {
                        mask |= partColors(p, mp);
                    }
                }
            }
        }
        return mask & WUBRG;
    }

    /** City of Brass: the damage a tap trigger of the card's own deals its controller, 0 for none, -1 unreadable. */
    private static int tapTriggerPain(final Card c) {
        int pain = 0;
        for (final Trigger t : c.getTriggers()) {
            if (t.getMode() != TriggerType.Taps || !"Card.Self".equals(t.getParam("ValidCard"))) {
                continue;
            }
            final String ex = t.getParam("Execute");
            final Map<String, String> p = params(ex == null ? null : c.getSVar(ex));
            if ("DealDamage".equals(api(p)) && "You".equals(p.get("Defined"))) {
                final Integer d = number(p.get("NumDmg"));
                if (d == null) {
                    return -1;
                }
                pain += d;
            } else if ("LoseLife".equals(api(p)) && "You".equals(p.get("Defined"))) {
                final Integer d = number(p.get("LifeAmount"));
                if (d == null) {
                    return -1;
                }
                pain += d;
            }
        }
        return pain;
    }

    /** City of Traitors: a land-played trigger of its own whose execute sacrifices it. */
    private static boolean sacrificedByLandPlay(final Card c) {
        for (final Trigger t : c.getTriggers()) {
            if (t.getMode() != TriggerType.LandPlayed) {
                continue;
            }
            final String ex = t.getParam("Execute");
            final Map<String, String> p = params(ex == null ? null : c.getSVar(ex));
            if ("Sacrifice".equals(api(p)) && !p.containsKey("SacValid")) {
                return true;
            }
        }
        return false;
    }

    /** Artifacts the AI controls that stay: none sacrifices itself, leaves at end of turn or is phased out (L3). */
    private static int lastingArtifacts(final Player ai, final Removal taken) {
        int n = 0;
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            try {
                if (c.isArtifact() && !taken.takes(c) && !c.isPhasedOut() && !c.hasSVar("EndOfTurnLeavePlay")
                        && !sacrificesItself(c)) {
                    n++;
                }
            } catch (final AITimeoutTracker.Expired e) {
                throw e;
            } catch (final RuntimeException e) {
                // an unreadable artifact does not count toward metalcraft
            }
        }
        return n;
    }

    private static boolean sacrificesItself(final Card c) {
        for (final SpellAbility sa : c.getSpellAbilities()) {
            final Cost cost = sa.getPayCosts();
            if (cost == null) {
                continue;
            }
            for (final CostPart part : cost.getCostParts()) {
                if (part instanceof CostSacrifice && part.payCostFromSource()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The land drop still to come, as one unit, or 0: every land in the hand has a painless mana ability whose colors
     * its script declares (no activator, no chosen color, no commander, no Special or reflected mana) and no triggered
     * ability (a Karoo's or Lotus Field's entry, a Temple's scry); the unit's colors are those every hand land makes,
     * colorless (generic only) when they share none. The caller asks only on the AI's own turn before its end step
     * with a land play left (D6).
     */
    private static int landDrop(final Player ai, final Removal taken) {
        int shared = WUBRG | C;
        boolean any = false;
        for (final Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (!c.isLand() || taken.takes(c)) {
                continue;
            }
            any = true;
            if (!c.getTriggers().isEmpty()) {
                return 0;
            }
            final int mask = handLandColors(ai, c);
            if (mask == 0) {
                return 0;
            }
            shared &= mask;
        }
        if (!any) {
            return 0;
        }
        return (shared & WUBRG) != 0 ? shared & WUBRG : C;
    }

    /** A land in hand: the colors its painless, unrestricted, unconditional mana abilities declare, or 0. */
    private static int handLandColors(final Player ai, final Card c) {
        int mask = 0;
        for (final SpellAbility ma : ComputerUtilMana.getAIPlayableMana(c)) {
            final Cost cost = ma.getPayCosts();
            boolean ok = true;
            if (cost != null) {
                for (final CostPart part : cost.getCostParts()) {
                    ok &= part instanceof CostTap;
                }
            }
            final SpellAbilityRestriction r = ma.getRestrictions();
            if (!ok || restrictedMana(ma) || r != null && cannotActivateAtUpkeep(r, false) || ma.isSuppressed()) {
                continue;
            }
            int declared = 0;
            boolean painful = false;
            for (SpellAbility p = ma; p != null; p = p.getSubAbility()) {
                final AbilityManaPart mp = p.getManaPart();
                if (mp == null) {
                    painful |= "You".equals(p.getParam("Defined"))
                            && (p.getApi() == ApiType.DealDamage || p.getApi() == ApiType.LoseLife);
                    continue;
                }
                if (!conditional(p)) {
                    declared |= declaredColors(mp);
                }
            }
            if (!painful && ma.getApi() == ApiType.Mana) {
                try {
                    if (r == null || r.checkOtherRestrictions(c, ma, ai)) {
                        mask |= declared;
                    }
                } catch (final AITimeoutTracker.Expired e) {
                    throw e;
                } catch (final RuntimeException e) {
                    // a restriction that cannot be read for a card in hand: the ability is not counted
                }
            }
        }
        return mask;
    }

    /** A mana part with a condition (Gemstone Caverns' luck counter): its colors are not read for a hand land. */
    private static boolean conditional(final SpellAbility p) {
        for (final String key : p.getMapParams().keySet()) {
            if (key.startsWith("Condition")) {
                return true;
            }
        }
        return false;
    }

    /** The colors a mana part's script declares, read without an activator; 0 when they need one. */
    private static int declaredColors(final AbilityManaPart mp) {
        final String produced = mp.getOrigProduced();
        if (produced == null || mp.isSpecialMana() || produced.contains("Chosen")) {
            return 0;
        }
        if (mp.isAnyMana()) {
            return WUBRG;
        }
        final String list = mp.isComboMana() ? produced.substring("Combo".length()) : produced;
        int mask = 0;
        for (final String token : list.trim().split("\\s+")) {
            final int bit = letterBit(token);
            if (bit == 0) {
                return 0; // ColorIdentity, ColorID, NotedColors and the like need an activator or a choice
            }
            mask |= bit;
        }
        return mask;
    }

    // ---------------------------------------------------------------------------------- will it untap (1.5, D24)

    /** The untap restrictions read once per count: whether none counts, and the cards an aura or effect stops. */
    static final class Restrictors {
        static final Restrictors NONE = new Restrictors(false, Collections.emptyList());

        final boolean all;
        final List<Card> stopped;

        Restrictors(final boolean all, final List<Card> stopped) {
            this.all = all;
            this.stopped = stopped;
        }

        boolean stops(final Card c) {
            for (final Card x : stopped) {
                if (x == c) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Both battlefields' printed untap restrictions (one scoped to what the permanent is attached to stops that
         * card; any other scope, or a skipped untap step, stops every source), the command zones' effect cards (one
         * scoped to what it remembers stops those cards; any other stops every source), and the AI's own UntapAdjust
         * and OnlyUntapChosen keywords (Winter Orb, Static Orb). No source counts past a global restrictor: the rule
         * does not narrow a restriction by its filter, which only refuses more.
         */
        static Restrictors read(final Player ai) {
            final Game game = ai.getGame();
            boolean all = false;
            final List<Card> stopped = new ArrayList<>();
            for (final KeywordInterface ki : ai.getKeywords()) {
                final String kw = ki.getOriginal();
                if (kw != null && (kw.startsWith("UntapAdjust") || kw.startsWith("OnlyUntapChosen"))) {
                    all = true;
                }
            }
            for (final Card p : game.getCardsIn(ZoneType.Battlefield)) {
                final CardRules rules = p.getRules();
                if (rules == null) {
                    continue;
                }
                for (final ICardFace face : rules.getAllFaces()) {
                    if (face == null || face.getReplacements() == null) {
                        continue;
                    }
                    for (final String line : face.getReplacements()) {
                        final Map<String, String> r = params(line);
                        if (untapStop(r.get("Event"), r.get("Layer"))) {
                            final String valid = r.getOrDefault("ValidCard", "");
                            if (valid.startsWith("Card.Self")) {
                                continue; // the permanent's own: step 3 reads it
                            }
                            if (attachedScope(valid)) {
                                final Card to = p.getAttachedTo();
                                if (to != null) {
                                    stopped.add(to);
                                }
                            } else {
                                all = true;
                            }
                        } else if (skipsUntap(r.get("Event"), r.get("Phase"), r.get("Skip"))) {
                            all = true;
                        }
                    }
                }
            }
            for (final Card e : game.getCardsIn(ZoneType.Command)) {
                if (!e.isImmutable()) {
                    continue;
                }
                for (final ReplacementEffect re : e.getReplacementEffects()) {
                    if (re.getMode() == ReplacementType.Untap && "CantHappen".equals(re.getParam("Layer"))) {
                        final String valid = re.getParam("ValidCard");
                        if (valid != null && valid.startsWith("Card.IsRemembered")) {
                            for (final Object o : e.getRemembered()) {
                                if (o instanceof Card) {
                                    stopped.add((Card) o);
                                }
                            }
                        } else {
                            all = true;
                        }
                    } else if (re.getMode() == ReplacementType.BeginPhase
                            && skipsUntap("BeginPhase", re.getParam("Phase"), re.getParam("Skip"))) {
                        all = true;
                    }
                }
            }
            return new Restrictors(all, stopped);
        }
    }

    /**
     * Step 3 of the untap read (DESIGN.md 1.5): a face of the card's printed card holds an untap restriction on itself
     * (Mana Vault, Grim Monolith, Basalt Monolith; a conditional one counts). Static data, read by script line.
     */
    static boolean printedSelfRestriction(final Card c) {
        final CardRules rules = c.getRules();
        if (rules == null) {
            return false;
        }
        for (final ICardFace face : rules.getAllFaces()) {
            if (face == null || face.getReplacements() == null) {
                continue;
            }
            for (final String line : face.getReplacements()) {
                final Map<String, String> r = params(line);
                if (untapStop(r.get("Event"), r.get("Layer")) && r.getOrDefault("ValidCard", "").startsWith("Card.Self")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean untapStop(final String event, final String layer) {
        return "Untap".equals(event) && "CantHappen".equals(layer);
    }

    private static boolean skipsUntap(final String event, final String phase, final String skip) {
        return "BeginPhase".equals(event) && "Untap".equals(phase) && "True".equals(skip);
    }

    private static boolean attachedScope(final String valid) {
        return valid.contains("EnchantedBy") || valid.contains("EquippedBy") || valid.contains("AttachedBy");
    }

    /**
     * Whether the card untaps for certain at the AI's next untap step, read without {@code Card.canUntap}, whose
     * replacement scan can build a card state's effects (D24): not phased out or exerted, no "doesn't untap" or
     * "returns at untap" keyword, no stun counter, no Phasing on it or on what it is attached to (B4), not a copy and
     * with a printed card, no untap restriction of its own printed or added (M29(c): only CardTraitChanges entries, the
     * land-trait ones skipped), and no restrictor that stops it.
     */
    static boolean willUntap(final Player ai, final Card c, final Restrictors stops) {
        if (c.isPhasedOut() || c.isExertedBy(ai)) {
            return false;
        }
        if (c.hasKeyword(DOESNT_UNTAP_NEXT) || c.hasKeyword(DOESNT_UNTAP) || c.hasKeyword(RETURNS_AT_UNTAP)) {
            return false;
        }
        if (c.getCounters(CounterEnumType.STUN) > 0) {
            return false;
        }
        final Card host = c.getAttachedTo();
        if (c.hasKeyword(Keyword.PHASING) || host != null && host.hasKeyword(Keyword.PHASING)) {
            return false;
        }
        if (c.isCloned() || c.getRules() == null || printedSelfRestriction(c)) {
            return false;
        }
        for (final ICardTraitChanges changes : c.getChangedCardTraitsList(c.getCurrentState())) {
            if (!(changes instanceof CardTraitChanges)) {
                continue;
            }
            for (final ReplacementEffect re : ((CardTraitChanges) changes).getReplacements()) {
                if (re.getMode() == ReplacementType.Untap && "CantHappen".equals(re.getParam("Layer"))) {
                    return false;
                }
            }
        }
        return !stops.stops(c);
    }

    // --------------------------------------------------------------------------------------------- verdicts (1.11)

    /** A copy's judged kind and cost (DESIGN.md 1.8). */
    record Copied(Kind kind, String cost) {
    }

    /**
     * What one judgment read, for the trace (DelayedLossTrace; DESIGN.md 6.4, RULINGS-R3 F): whether the play is a
     * delayed-loss card or copies one, the debts the reader found (its last answer in the judgment), and each base
     * count the judgment made, with the AI's life and the threat it was made with. As an input, whether the counts
     * leave the threat term out: the trace's second judgment of a refusal, which tells one the threat term alone
     * decides. Only the trace makes one; a judgment asked without one notes nothing, and its reads are the same either
     * way.
     */
    static final class Seen {
        /** The counts leave the threat term out. */
        final boolean threatFree;
        /** The play is a delayed-loss card, or copies one. */
        boolean flagged;
        /** The reader's last answer in the judgment. */
        Debts debts = Debts.NONE;
        /** Each base count the judgment made, in order. */
        final List<Count> counts = new ArrayList<>();
        /** The threat term of the last count made. */
        int threat;

        Seen(final boolean threatFree) {
            this.threatFree = threatFree;
        }

        void counted(final Due due, final Player ai, final Mana mana) {
            counts.add(new Count(due, ai.getLife(), threat, mana));
        }

        /** Whether a count for the next upkeep was made, the only count a threat term enters. */
        boolean countedNext() {
            for (final Count c : counts) {
                if (c.due() == Due.NEXT) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A base count a judgment made: its debt's due time, the AI's life, the threat it was made with, its mana. */
    record Count(Due due, int life, int threat, Mana mana) {
    }

    /**
     * Why the play must not be made now, or null. A Pact whose debt the next upkeep cannot pay, or with another debt
     * pending; an extra turn that ends in a loss; a loss at an end step; a copy of one, or of a pay-or-lose trigger; a
     * cost that would uncover a pending debt, or spend the mana of one due now. Reads only (DESIGN.md 1.10); the plan
     * only for a Pact.
     */
    public static String problem(final Player ai, final SpellAbility sa, final Supplier<ComboPlan> plan) {
        return problem(ai, sa, plan, null);
    }

    /** {@link #problem}, noting what it read in {@code seen} when the trace watches it (null: nothing is noted). */
    static String problem(final Player ai, final SpellAbility sa, final Supplier<ComboPlan> plan, final Seen seen) {
        if (ai == null || sa == null) {
            return null;
        }
        final Kind kind = kind(sa);
        if (kind != null) {
            if (seen != null) {
                seen.flagged = true;
            }
            return judge(ai, sa, kind, ComboGuard.pactCost(sa), plan, seen);
        }
        final Copied copied = copiedFlagged(sa);
        if (copied != null) {
            if (seen != null) {
                seen.flagged = true;
            }
            return judge(ai, sa, copied.kind(), copied.cost(), plan, seen);
        }
        return protection(ai, sa.getPayCosts(), sa, true, seen);
    }

    /**
     * DESIGN.md 1.7, debt protection: null, {@link #DEBT_UNPROTECTED} or {@link #DEBT_DUE_NOW}. While the reader finds
     * a debt, a play whose cost can take what the count relies on (a counted source, the hand land the land drop
     * needs, the life the pain needs) is refused unless the debt stays covered without it, with each of the payment's
     * choices taken in turn; a debt not covered before such a play is not covered after it. While a debt is due now
     * (a Pact's trigger on the stack), the count is of what is untapped now, a tap is a removal too, a mana part of
     * mana value T is allowed only while the painless sources cover the debt whichever T of them the payment taps,
     * and an X is refused. When both kinds are owed, both must stay covered.
     * <p>
     * RULINGS-R3 M17: while a debt's cover needs a painful unit (the painless count does not cover it), the life the
     * play's own effects cost (a LoseLife or DealDamage of its own), 2 for each Phyrexian shard of its mana cost (T2
     * review Minor 8) and T times the largest pain of an untapped painful source (the play's own mana payment may tap
     * it) come off the life budget too; an amount that is not a number refuses the play. RULINGS-R3 M18: a board-wide
     * removal of the play's own (a DestroyAll, a SacrificeAll, a ChangeZoneAll from the battlefield) takes the counted
     * sources its filter matches.
     * <p>
     * The reader comes first: in a game where no Pact was cast it finds nothing after two empty scans, and the cost is
     * never looked at.
     */
    static String protection(final Player ai, final Cost cost, final SpellAbility judged) {
        return protection(ai, cost, judged, true, null);
    }

    /**
     * Debt protection for a play ({@code play}: its own effects are read too, M17 (i) and M18), or for an unless cost
     * the AI is about to pay (S6b: the asking ability's effects are what the payment stops, so they are not read).
     */
    private static String protection(final Player ai, final Cost cost, final SpellAbility judged, final boolean play,
                                     final Seen seen) {
        final Debts owed = owed(ai, judged);
        if (seen != null) {
            seen.debts = owed;
        }
        if (owed.isEmpty()) {
            return null;
        }
        final int t = manaValue(cost);
        if (owed.hasNow()) {
            final String now = guard(ai, cost, judged, play, Due.NOW, owed.now(), t, seen);
            if (now != null) {
                return now;
            }
        }
        return owed.hasNext() ? guard(ai, cost, judged, play, Due.NEXT, owed.next(), t, seen) : null;
    }

    /** Debt protection against the debts due at one time (DESIGN.md 1.7, RULINGS-R3 M17 and M18). */
    private static String guard(final Player ai, final Cost cost, final SpellAbility judged, final boolean play,
                                final Due due, final Debt debt, final int t, final Seen seen) {
        final boolean now = due == Due.NOW;
        final String code = now ? DEBT_DUE_NOW : DEBT_UNPROTECTED;
        if (now && t < 0) {
            return code; // an X is chosen after the decision: it may spend anything
        }
        final Removal cut = Removal.of(ai, cost, judged, due);
        final boolean spends = now && t > 0; // for a debt due next upkeep mana costs nothing: every counted source untaps
        final boolean wipes = play && wipesBoard(judged);                         // M18, the play's own effects
        final Integer ownLoss = play ? plus(ownLifeLoss(judged), phyrexianLife(cost)) // M17 (i); null: not a number
                : Integer.valueOf(0);
        final int manaPain = t != 0 ? largestUntappedPain(ai) : 0;  // M17 (ii); -1: unreadable
        if (cut.isEmpty() && !spends && !wipes && ownLoss != null && ownLoss == 0 && manaPain == 0) {
            return null;
        }
        final Mana base = count(ai, due, Removal.NONE, seen);
        if (seen != null) {
            seen.counted(due, ai, base);
        }
        final List<Card> counted = base.cards();
        final List<Card> wiped = wipes ? boardWide(ai, judged, counted) : Collections.emptyList();
        final Removal removal = wiped.isEmpty() ? cut : cut.plus(wiped, 0);
        final boolean takes = !removal.isEmpty() || spends;
        final Predicate<Card> relevant = c -> counted.contains(c) || c.isArtifact() || c.isInZone(ZoneType.Hand);
        for (final Removal taken : removal.cases(relevant)) {
            final Mana left = count(ai, due, taken, seen);
            if (!left.pays(debt)) {
                if (!takes) {
                    continue; // the play only loses life, and the debt was not covered before it: M17 asks nothing
                }
                return code; // uncovered after the play: what it takes, or what it spends, the debt needed
            }
            if (!left.painless().pays(debt)) { // the cover needs a painful unit (M17)
                if (removal.lifeUnread() || ownLoss == null || manaPain < 0 || t < 0 && manaPain > 0) {
                    return code; // a life amount that is not a number
                }
                final int extra = ownLoss + Math.max(0, t) * manaPain;
                if (extra > 0 && !count(ai, due, taken.plus(Collections.emptyList(), extra), seen).pays(debt)) {
                    return code; // the play's own life loss, and its mana's pain, leave the pain no margin
                }
            }
            if (spends && !left.painless().paysAfterAnyTapped(debt, t)) {
                return code; // 1.7: whichever t painless sources the payment taps, the rest must cover the debt
            }
        }
        return null;
    }

    /** One part of a play's script, and whether a Repeat or RepeatEach above it runs it an unknown number of times. */
    static final class Part {
        final SpellAbility sa;
        final boolean repeated;

        Part(final SpellAbility sa, final boolean repeated) {
            this.sa = sa;
            this.repeated = repeated;
        }
    }

    /**
     * Every part of a play's script, in a fixed order (T2 review finding 2): the play and its sub-abilities, and after
     * each part the abilities it holds as additional abilities, with their own sub-abilities and so on: a Repeat's or
     * RepeatEach's {@code RepeatSubAbility} (Ad Nauseam's loss hangs there), a Charm's {@code Choices}, a coin flip's,
     * a clash's or a branch's outcomes, a delayed trigger's {@code Execute}, a die roll's results. Each part's maps are
     * read in their keys' sorted order, the single abilities before the lists, to {@link #REACH_DEPTH} levels; a part
     * under a {@code RepeatSubAbility} is marked repeated. Null when a part at that depth holds more: what cannot be
     * read is not counted on. Field and map reads only: no ability is built, nothing is drawn.
     */
    static List<Part> parts(final SpellAbility play) {
        final List<Part> out = new ArrayList<>();
        return addParts(play, false, REACH_DEPTH, out) ? out : null;
    }

    private static boolean addParts(final SpellAbility first, final boolean repeated, final int depth,
                                    final List<Part> out) {
        for (SpellAbility p = first; p != null; p = p.getSubAbility()) {
            out.add(new Part(p, repeated));
            final Map<String, SpellAbility> single = p.getAdditionalAbilities();
            final Map<String, List<AbilitySub>> lists = p.getAdditionalAbilityLists();
            final boolean holdsSingle = single != null && !single.isEmpty();
            final boolean holdsLists = lists != null && !lists.isEmpty();
            if (!holdsSingle && !holdsLists) {
                continue;
            }
            if (depth <= 0) {
                return false;
            }
            if (holdsSingle) {
                for (final String key : sortedKeys(single)) {
                    if (!addParts(single.get(key), repeated || "RepeatSubAbility".equals(key), depth - 1, out)) {
                        return false;
                    }
                }
            }
            if (holdsLists) {
                for (final String key : sortedKeys(lists)) {
                    for (final AbilitySub sub : lists.get(key)) {
                        if (!addParts(sub, repeated, depth - 1, out)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /** A map's keys, sorted: the order a part's additional abilities are read in, whatever the map's own. */
    private static List<String> sortedKeys(final Map<String, ?> map) {
        final List<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);
        return keys;
    }

    /**
     * RULINGS-R3 M17 (i): the life the play's own effects cost the AI: every LoseLife of its own (not targeted,
     * {@code Defined$} absent, You or Player) and every DealDamage with {@code Defined$} You or Player among its
     * {@link #parts}, along its sub-abilities or under an additional ability (Ad Nauseam's, under its Repeat; every
     * mode of a Charm). Null when such an amount is not a number (an X, a count), when a Repeat runs it an unknown
     * number of times (Hoarder's Greed), or when the parts nest deeper than they are read: it cannot be budgeted.
     * Script parameters only.
     */
    static Integer ownLifeLoss(final SpellAbility play) {
        final List<Part> parts = parts(play);
        if (parts == null) {
            return null;
        }
        int loss = 0;
        for (final Part part : parts) {
            final SpellAbility p = part.sa;
            final String amount;
            if (p.getApi() == ApiType.LoseLife && !p.usesTargeting() && own(p.getParam("Defined"), true)) {
                amount = p.getParam("LifeAmount");
            } else if (p.getApi() == ApiType.DealDamage && own(p.getParam("Defined"), false)) {
                amount = p.getParam("NumDmg");
            } else {
                continue;
            }
            final Integer n = part.repeated ? null : number(amount);
            if (n == null) {
                return null;
            }
            loss += n;
        }
        return loss;
    }

    /**
     * RULINGS-R3 M18: the counted sources the board-wide removals of the AI's own play take, each matched by its
     * filter over the counted sources only ({@code CardLists.getValidCards}), among all its {@link #parts} (every mode
     * of a Charm: Austere Command's artifact mode is one). Every counted source when the parts nest deeper than they
     * are read, or when a filter cannot be read (T2 review Minor 6: what cannot be read is not counted on, as an
     * unreadable cost part takes every card it may; the throw would leave {@link #problem}, and a site's catch lets a
     * play nothing flags through).
     */
    static List<Card> boardWide(final Player ai, final SpellAbility play, final List<Card> counted) {
        final List<Part> parts = parts(play);
        if (parts == null) {
            return new ArrayList<>(counted);
        }
        final List<Card> out = new ArrayList<>();
        for (final Part part : parts) {
            final SpellAbility p = part.sa;
            final String valid = wipeFilter(p);
            if (valid == null) {
                continue;
            }
            final List<Card> matched;
            try {
                matched = CardLists.getValidCards(counted, valid, ai, p.getHostCard(), p);
            } catch (final AITimeoutTracker.Expired e) {
                throw e;
            } catch (final RuntimeException e) {
                return new ArrayList<>(counted);
            }
            for (final Card c : matched) {
                if (!out.contains(c)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /**
     * Whether the play's {@link #parts} hold a board-wide removal (M18), or nest deeper than they are read. Script
     * parameters only.
     */
    private static boolean wipesBoard(final SpellAbility play) {
        final List<Part> parts = parts(play);
        if (parts == null) {
            return true;
        }
        for (final Part part : parts) {
            if (wipeFilter(part.sa) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The filter of a board-wide removal: a DestroyAll's or SacrificeAll's {@code ValidCards$}, or the
     * {@code ChangeType$} of a ChangeZoneAll from the battlefield; null for any other part.
     */
    private static String wipeFilter(final SpellAbility p) {
        final ApiType api = p.getApi();
        if (api == ApiType.DestroyAll || api == ApiType.SacrificeAll) {
            return p.getParam("ValidCards");
        }
        if (api == ApiType.ChangeZoneAll) {
            final String origin = p.getParam("Origin");
            return origin != null && origin.contains("Battlefield") ? p.getParam("ChangeType") : null;
        }
        return null;
    }

    /**
     * RULINGS-R3 M17 (i), T2 review Minor 8 (the controller's ruling): the life a cost's Phyrexian shards may take,
     * {@link #PHYREXIAN_LIFE} each. The AI pays a shard with life when no source of its pays it, so the rule counts the
     * life as the play's own loss: it only refuses more, and only while the cover needs a painful unit. A script's
     * {@code AIPhyrexianPayment$} is not read (Gitaxian Probe's Never counts too). Cost and shard reads only.
     */
    static int phyrexianLife(final Cost cost) {
        if (cost == null) {
            return 0;
        }
        int life = 0;
        for (final CostPart part : cost.getCostParts()) {
            if (part instanceof CostPartMana) {
                final ManaCost mana = ((CostPartMana) part).getMana();
                if (mana != null) {
                    life += PHYREXIAN_LIFE * mana.getPhyrexianCount();
                }
            }
        }
        return life;
    }

    /** {@code a + b}, or null when {@code a}, a life amount, is not a number. */
    private static Integer plus(final Integer a, final int b) {
        return a == null ? null : Integer.valueOf(a + b);
    }

    /** A {@code Defined$} that takes in the AI itself: You, or Player (each player); absent where that means You. */
    private static boolean own(final String defined, final boolean absentIsYou) {
        return defined == null ? absentIsYou : "You".equals(defined) || "Player".equals(defined);
    }

    /**
     * RULINGS-R3 M17 (ii): the most life one tap of an untapped mana source of the AI's costs (a life payment, a
     * self-damage sub-ability, City of Brass's tap trigger): what each mana of a payment the play makes now may cost.
     * -1 when a source cannot be read. The same reads as the count's.
     */
    static int largestUntappedPain(final Player ai) {
        int largest = 0;
        for (final Card c : ai.getCardsIn(ZoneType.Battlefield)) {
            if (c.isTapped() || c.isPhasedOut()) {
                continue;
            }
            try {
                final int tapPain = tapTriggerPain(c);
                if (tapPain < 0) {
                    return -1;
                }
                for (final SpellAbility ma : ComputerUtilMana.getAIPlayableMana(c)) {
                    int pain = selfDamage(ma);
                    boolean taps = false;
                    final Cost cost = ma.getPayCosts();
                    if (cost != null) {
                        for (final CostPart part : cost.getCostParts()) {
                            if (part instanceof CostTap) {
                                taps = true;
                            } else if (part instanceof CostPayLife) {
                                pain += Math.max(0, part.getAbilityAmount(ma));
                            }
                        }
                    }
                    largest = Math.max(largest, pain + (taps ? tapPain : 0));
                }
            } catch (final AITimeoutTracker.Expired e) {
                throw e;
            } catch (final RuntimeException e) {
                return -1;
            }
        }
        return largest;
    }

    /** DESIGN.md 1.7's T: the mana value of a cost's mana part, 0 without one, -1 with an X (chosen later). */
    static int manaValue(final Cost cost) {
        if (cost == null) {
            return 0;
        }
        int t = 0;
        for (final CostPart part : cost.getCostParts()) {
            if (part instanceof CostPartMana) {
                final ManaCost mana = ((CostPartMana) part).getMana();
                if (mana == null) {
                    continue;
                }
                if (mana.countX() > 0) {
                    return -1;
                }
                t += mana.getCMC();
            }
        }
        return t;
    }

    /**
     * DESIGN.md 1.8 (D26, round 2's M8): the kind and cost a copy is judged by, or null when the play copies nothing
     * flagged. A play with a {@code CopySpellAbility} anywhere in its chain copies its target spells and abilities on
     * the stack, or, while it has no target yet, any item on the stack. A flagged spell is judged as that kind, a Pact
     * with its own cost (the copy's debt is the AI's); a {@code LosesGame} with an {@code UnlessCost$} (a Pact's
     * pay-or-lose trigger, read through its WrappedAbility) as a Pact with that cost, which the trigger itself, a debt,
     * always refuses. Only the five Pacts' scripts have that shape (DESIGN.md 1.3). Script parameters, the targets and
     * the stack's items only; a play that copies nothing reads its chain's API words and nothing else.
     */
    static Copied copiedFlagged(final SpellAbility sa) {
        for (SpellAbility a = sa; a != null; a = a.getSubAbility()) {
            if (a.getApi() != ApiType.CopySpellAbility) {
                continue;
            }
            final List<SpellAbility> copied = new ArrayList<>();
            if (a.getTargets() != null) {
                for (final SpellAbility s : a.getTargets().getTargetSpells()) {
                    copied.add(s);
                }
            }
            final Card host = a.getHostCard();
            if (copied.isEmpty() && host != null && host.getGame() != null) {
                for (final SpellAbilityStackInstance si : host.getGame().getStack()) {
                    copied.add(si.getSpellAbility());
                }
            }
            for (final SpellAbility s : copied) {
                if (s == null) {
                    continue;
                }
                final Kind k = kind(s);
                if (k != null) {
                    return new Copied(k, k == Kind.PACT ? ComboGuard.pactCost(s) : null);
                }
                if (s.getApi() == ApiType.LosesGame && s.hasParam("UnlessCost")) {
                    return new Copied(Kind.PACT, s.getParam("UnlessCost"));
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------- resolution sites (S5, S5b, S6, S6b)

    /**
     * S5 and S5b (DESIGN.md D13): whether an optional free cast at resolution (cascade, Beseech the Mirror, Isochron
     * Scepter's copy, Discover) is declined: a flagged card, covered or not; a copy of one; or, while a debt is
     * pending, a play with any cost part beyond its waived mana. Counts no mana: resolution is not a priority decision.
     * Script parameters, the copy's targets and, for a cost beyond mana, the reader's two scans.
     */
    public static boolean declinesFreeCast(final Player ai, final SpellAbility sa) {
        return declinesFreeCast(ai, sa, null);
    }

    /** {@link #declinesFreeCast}, noting what it read in {@code seen} when the trace watches it (null: nothing). */
    static boolean declinesFreeCast(final Player ai, final SpellAbility sa, final Seen seen) {
        if (ai == null || sa == null) {
            return false;
        }
        if (kind(sa) != null || copiedFlagged(sa) != null) {
            if (seen != null) {
                seen.flagged = true;
            }
            return true;
        }
        if (!hasCostBeyondMana(sa.getPayCosts())) {
            return false;
        }
        final Debts owed = owed(ai, sa);
        if (seen != null) {
            seen.debts = owed;
        }
        return !owed.isEmpty();
    }

    /** Whether a cost has a part besides mana: a sacrifice, a discard, life, a tap. */
    static boolean hasCostBeyondMana(final Cost cost) {
        if (cost == null) {
            return false;
        }
        for (final CostPart part : cost.getCostParts()) {
            if (!(part instanceof CostPartMana)) {
                return true;
            }
        }
        return false;
    }

    /**
     * S6 (DESIGN.md D28, round 1's L4): the AI's held-mana memory sets, which its payment of its own Pact debt clears
     * first, so that every source the count relied on pays: the payment refuses a source held for a later spell
     * (ComputerUtilMana.isManaSourceReserved), and a hold can be made after the Pact was cast, or outlive the
     * opponent's turn. In this order: the next spell's, the second main phase's, the AI's declare-blockers step's,
     * the opponent's declare-blockers step's.
     */
    public static final List<AiCardMemory.MemorySet> HELD_MANA_SETS = List.of(
            AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_NEXT_SPELL, AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_MAIN2,
            AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_DECLBLK,
            AiCardMemory.MemorySet.HELD_MANA_SOURCES_FOR_ENEMY_DECLBLK);

    /**
     * S6 and S6b (DESIGN.md D28): whether the unless cost being paid is the AI's own pay-or-lose debt, its Pact's
     * {@code LosesGame} or a copy of it, by its shape alone: an own {@code LosesGame} with an {@code UnlessCost$},
     * which only the five Pacts' scripts hold (DESIGN.md 1.3). A WrappedAbility delegates all three reads. Script
     * parameters and the activator only.
     */
    public static boolean isOwnPactDebt(final Player ai, final SpellAbility sa) {
        return ai != null && sa != null && sa.getApi() == ApiType.LosesGame && sa.hasParam("UnlessCost")
                && ai.equals(sa.getActivatingPlayer());
    }

    /**
     * S6b (DESIGN.md D32, round 2's B1): whether an unless cost the AI is about to pay is declined because paying it
     * would spend a debt due now (whichever sources the payment taps) or uncover a pending one: debt protection asked
     * of the cost, never of the asking ability's effects, which are what the payment stops. Never the AI's own Pact
     * debt, which S6 pays. In a game where no Pact was cast the reader finds nothing and the cost is never read.
     */
    public static boolean declinesUnlessPayment(final Player ai, final SpellAbility sa, final Cost cost) {
        return unlessPaymentProblem(ai, sa, cost, null) != null;
    }

    /**
     * Why S6b declines the unless payment ({@link #DEBT_DUE_NOW} or {@link #DEBT_UNPROTECTED}), or null: never for the
     * AI's own Pact debt. {@code seen} notes what it read when the trace watches it (null: nothing).
     */
    static String unlessPaymentProblem(final Player ai, final SpellAbility sa, final Cost cost, final Seen seen) {
        return isOwnPactDebt(ai, sa) ? null : protection(ai, cost, sa, false, seen);
    }

    private static String judge(final Player ai, final SpellAbility sa, final Kind kind, final String cost,
                                final Supplier<ComboPlan> plan, final Seen seen) {
        switch (kind) {
            case PACT:
                if (libraryOutTurn(ai, plan == null ? null : plan.get())) {
                    return null; // today's waiver for Oops! All Spells (1.9)
                }
                final Debts owed = owed(ai, sa);
                if (seen != null) {
                    seen.debts = owed;
                }
                if (!owed.isEmpty()) {
                    return PACT_DEBT_PENDING; // 1.3: one debt per upkeep, NOW or NEXT
                }
                if (cost == null) {
                    return PACT_UNPAYABLE;
                }
                final Mana next = count(ai, Due.NEXT, Removal.NONE, seen);
                if (seen != null) {
                    seen.counted(Due.NEXT, ai, next);
                }
                return next.pays(Debt.of(cost)) ? null : PACT_UNPAYABLE;
            case EXTRA_TURN:
                return EXTRA_TURN_LOSES;
            default:
                return END_STEP_LOSES;
        }
    }

    /**
     * ComboGuard's library-out clause, the one waiver class (a) keeps (DESIGN.md 1.9): the AI's own turn, a
     * LIBRARY_OUT plan and at most {@link #PACT_LIBRARY_OUT_LEFT} cards in its library. Inert in Commander; kept for the
     * GUI's 60-card Oops! All Spells decks.
     */
    static boolean libraryOutTurn(final Player ai, final ComboPlan plan) {
        return ai.getGame().getPhaseHandler().getPlayerTurn() == ai && plan != null
                && plan.has(ComboPlan.Kind.LIBRARY_OUT) && ai.getCardsIn(ZoneType.Library).size() <= PACT_LIBRARY_OUT_LEFT;
    }

    /**
     * What a site answers when {@link #problem} threw (DESIGN.md 1.11): a Pact waits (ComboGuard.isPact names one whose
     * script cannot be read too), an extra turn or end-step loss that {@link #kind} reads is refused, any other play is
     * not. {@code AITimeoutTracker.Expired} is the caller's to rethrow first.
     */
    public static String refusalWhenUnread(final SpellAbility sa) {
        if (ComboGuard.isPact(sa)) {
            return PACT_UNPAYABLE;
        }
        final Kind k;
        try {
            k = kind(sa);
        } catch (final AITimeoutTracker.Expired e) {
            throw e;
        } catch (final RuntimeException e) {
            return null;
        }
        if (k == Kind.PACT) {
            return PACT_UNPAYABLE;
        }
        if (k == Kind.EXTRA_TURN) {
            return EXTRA_TURN_LOSES;
        }
        return k == Kind.END_STEP ? END_STEP_LOSES : null;
    }

    // ---------------------------------------------------------------------------------------------------- helpers

    private static int letterBit(final String token) {
        if (token == null || token.length() != 1) {
            return 0;
        }
        final int at = LETTERS.indexOf(token.charAt(0));
        if (at >= 0) {
            return LETTER_BITS[at];
        }
        return "C".equals(token) ? C : 0;
    }

    private static Integer number(final String s) {
        if (s == null || s.isEmpty() || !s.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Integer.parseInt(s);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** A mask as letters, for traces: "WUBRG", "C", "UR". */
    static String colors(final int mask) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < LETTERS.length(); i++) {
            if ((mask & LETTER_BITS[i]) != 0) {
                sb.append(LETTERS.charAt(i));
            }
        }
        if ((mask & C) != 0) {
            sb.append('C');
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }
}
