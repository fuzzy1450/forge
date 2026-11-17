package forge.game.staticability;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;

import org.apache.commons.lang3.tuple.Pair;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.simulation.GameCopier;
import forge.game.Game;
import forge.game.GameSnapshot;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.card.CounterEnumType;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.SimScope;

/**
 * The per-game static ability mode flags (Game#mayHaveStaticAbilityMode) and the scans that skip on them: every way a
 * mode comes to be noted, the answers a skipped scan gives, and (through ModeProbeChecker) that each skipped scan, run
 * anyway, finds nothing, moves no id counter and draws nothing, and that the scans that no longer build a
 * CardCollection visit the cards in the order it held them.
 */
public class StaticAbilityModeFlagsTest extends AITest {

    private ModeProbeChecker checker;

    @BeforeMethod
    public void bindScopeAndProbe() {
        SimScope.enter(new SimScope(4242L));
        checker = new ModeProbeChecker(1);
        checker.install();
    }

    @AfterMethod(alwaysRun = true)
    public void unbind() {
        ModeProbeChecker.uninstall();
        if (SimScope.current() != null) {
            SimScope.exit();
        }
    }

    private void assertNoMismatch() {
        assertTrue(checker.mismatches.isEmpty(), checker.summary() + " " + checker.mismatches);
    }

    private static Player ai(Game game) {
        return game.getPlayers().get(1);
    }

    private static Player opponent(Game game) {
        return game.getPlayers().get(0);
    }

    /** A game holding nothing with a static ability: every instrumented scan skips, with the old no-match answers. */
    @Test
    public void everyScanSkipsInAGameWithoutTheMode() {
        Game game = initAndCreateGame();
        Player p = ai(game), o = opponent(game);
        Card bears = addCard("Grizzly Bears", p);
        Card other = addCard("Grizzly Bears", o);
        SpellAbility spell = bears.getFirstSpellAbility();
        for (StaticAbilityMode m : StaticAbilityMode.values()) {
            assertFalse(game.mayHaveStaticAbilityMode(m), m.toString());
        }

        assertFalse(StaticAbilityCombatDamageToughness.combatDamageToughness(bears));
        assertFalse(StaticAbilityCantPreventDamage.cantPreventDamage(bears, true));
        assertFalse(StaticAbilityCantRegenerate.cantRegenerate(bears));
        assertFalse(StaticAbilityAssignNoCombatDamage.assignNoCombatDamage(bears));
        assertFalse(StaticAbilityCombatDamageNegatePower.combatDamageNegatePower(bears));
        assertFalse(StaticAbilityAssignCombatDamageAsUnblocked.assignCombatDamageAsUnblocked(bears));
        assertFalse(StaticAbilityAssignCombatDamageAsUnblocked.assignCombatDamageAsUnblocked(bears, false));
        Pair<Integer, Integer> minMax = StaticAbilityCantAttackBlock.getMinMaxBlocker(bears, o);
        assertEquals(minMax.getLeft(), Integer.valueOf(1));
        assertEquals(minMax.getRight(), Integer.valueOf(Integer.MAX_VALUE));
        assertEquals(StaticAbilityBlockRestrict.blockRestrictNum(o), Integer.MAX_VALUE);
        assertFalse(StaticAbilityCantAttackBlock.cantBlock(other));
        assertFalse(StaticAbilityCantAttackBlock.cantBlockBy(bears, other));
        assertFalse(StaticAbilityCantAttackBlock.cantBlockBy(bears, null));
        List<SpellAbility> alt1 = StaticAbilityAlternativeCost.alternativeCosts(spell, bears, p);
        List<SpellAbility> alt2 = StaticAbilityAlternativeCost.alternativeCosts(spell, bears, p);
        assertTrue(alt1.isEmpty());
        assertTrue(alt1 != alt2, "a fresh list each call, as before");
        alt1.add(spell); // the caller may write to it
        assertTrue(StaticAbilityMustAttack.entitiesMustAttack(bears).isEmpty());
        assertFalse(StaticAbilityCantAttackBlock.canBlockIfReach(bears, other));
        assertFalse(StaticAbilityCantAttackBlock.canBlockIfShadow(bears, other));
        assertFalse(StaticAbilityCantGainLosePayLife.anyCantLoseLife(p));
        assertFalse(StaticAbilityCantGainLosePayLife.anyCantGainLife(p));
        assertFalse(StaticAbilityCantGainLosePayLife.anyCantPayLife(p, false, null));
        assertEquals(StaticAbilityDevotion.getDevotionMod(p), 0);
        assertFalse(StaticAbilityCantPutCounter.anyCantPutCounter(bears, CounterEnumType.P1P1, null));
        assertFalse(StaticAbilityCantPutCounter.anyCantPutCounter(p, CounterEnumType.P1P1));
        assertFalse(StaticAbilityInfectDamage.isInfectDamage(o));
        assertFalse(StaticAbilityCantAttackBlock.cantAttack(bears, o));
        assertNull(StaticAbilityCantTarget.cantTarget(other, spell));
        assertFalse(StaticAbilityIgnoreLegendRule.ignoreLegendRule(bears));

        assertEquals(checker.skips.get(), 27L, checker.summary());
        assertEquals(checker.scopedSkips.get(), 27L, checker.summary());
        assertNoMismatch();
    }

    /** The menace pair is built before the skip and returned as built. */
    @Test
    public void theMinMaxSkipReturnsTheMenacePair() {
        Game game = initAndCreateGame();
        Card menace = addCard("Grizzly Bears", ai(game));
        menace.addChangedCardKeywords(List.of("Menace"), null, false, game.getNextTimestamp(), null);
        menace.updateKeywordsCache();
        assertFalse(game.mayHaveStaticAbilityMode(StaticAbilityMode.MinMaxBlocker));
        Pair<Integer, Integer> minMax = StaticAbilityCantAttackBlock.getMinMaxBlocker(menace, opponent(game));
        assertEquals(minMax.getLeft(), Integer.valueOf(2));
        assertEquals(minMax.getRight(), Integer.valueOf(Integer.MAX_VALUE));
        assertEquals(checker.skips.get(), 1L);
        assertNoMismatch();
    }

    /** A card in a library notes its modes when it is made; the scan runs and finds it once it is on the battlefield. */
    @Test
    public void aLibraryCardsModeIsNotedAndFoundOnceItIsOnTheBattlefield() {
        Game game = initAndCreateGame();
        Player p = ai(game);
        Card bears = addCard("Grizzly Bears", p);
        assertFalse(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantPreventDamage));
        Card torment = addCardToZone("Everlasting Torment", p, ZoneType.Library);
        assertTrue(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantPreventDamage));
        assertTrue(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantGainLife));
        assertFalse(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));

        assertFalse(StaticAbilityCantPreventDamage.cantPreventDamage(bears, false)); // scanned: not in play yet
        assertEquals(checker.skips.get(), 0L);

        p.getZone(ZoneType.Library).remove(torment);
        p.getZone(ZoneType.Battlefield).add(torment);
        assertTrue(StaticAbilityCantPreventDamage.cantPreventDamage(bears, false));
        assertTrue(StaticAbilityCantGainLosePayLife.anyCantGainLife(p));
        assertFalse(StaticAbilityCantRegenerate.cantRegenerate(bears)); // skipped
        assertEquals(checker.skips.get(), 1L);
        assertNoMismatch();
    }

    /** An effect card made mid-game with a static ability of the mode. */
    @Test
    public void anEffectCardMadeMidGameNotesItsMode() {
        Game game = initAndCreateGame();
        Player p = ai(game);
        Card bears = addCard("Grizzly Bears", p);
        assertFalse(StaticAbilityCantRegenerate.cantRegenerate(bears));
        assertEquals(checker.skips.get(), 1L);

        Card effect = new Card(game.nextCardId(), null, game);
        effect.setOwner(p);
        effect.setName("No Regeneration Effect");
        effect.addStaticAbility("Mode$ CantRegenerate | ValidCard$ Creature | EffectZone$ Command");
        p.getZone(ZoneType.Command).add(effect);
        assertTrue(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        assertTrue(StaticAbilityCantRegenerate.cantRegenerate(bears));
        assertEquals(checker.skips.get(), 1L);
        assertNoMismatch();
    }

    /** A keyword granted mid-game brings its static ability (Defender: CantAttack). */
    @Test
    public void aKeywordGrantedMidGameNotesItsMode() {
        Game game = initAndCreateGame();
        Card bears = addCard("Grizzly Bears", ai(game));
        assertFalse(StaticAbilityCantAttackBlock.cantAttack(bears, opponent(game)));
        assertEquals(checker.skips.get(), 1L);

        bears.addChangedCardKeywords(List.of("Defender"), null, false, game.getNextTimestamp(), null);
        bears.updateKeywordsCache();
        assertTrue(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantAttack));
        assertTrue(StaticAbilityCantAttackBlock.cantAttack(bears, opponent(game)));
        assertEquals(checker.skips.get(), 1L);
        assertNoMismatch();
    }

    /** A static ability another one grants (AddStaticAbility$) is made, and notes its mode, when it is granted. */
    @Test
    public void aGrantedStaticAbilityNotesItsMode() {
        Game game = initAndCreateGame();
        Player p = ai(game);
        Card bears = addCard("Grizzly Bears", p);
        Card lord = addCard("Grizzly Bears", p);
        lord.setSVar("NoRegen", "Mode$ CantRegenerate | ValidCard$ Card.Self | Description$ no regeneration");
        lord.addStaticAbility("Mode$ Continuous | Affected$ Creature.Other | AddStaticAbility$ NoRegen");
        assertTrue(game.mayHaveStaticAbilityMode(StaticAbilityMode.Continuous));
        assertFalse(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        assertFalse(StaticAbilityCantRegenerate.cantRegenerate(bears));
        assertEquals(checker.skips.get(), 1L);

        game.getAction().checkStaticAbilities(); // skips scans of its own
        assertTrue(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        long skips = checker.skips.get();
        assertTrue(StaticAbilityCantRegenerate.cantRegenerate(bears));
        assertFalse(StaticAbilityCantRegenerate.cantRegenerate(lord));
        assertEquals(checker.skips.get(), skips);
        assertNoMismatch();
    }

    /** A copy of a game starts with every mode the game noted, even one no card of it has any more. */
    @Test
    public void aGameCopyStartsWithTheGamesModes() {
        Game game = initAndCreateGame();
        Player p = ai(game);
        Card bears = addCard("Grizzly Bears", p);
        addCard("Everlasting Torment", p);
        Card gone = addCard("Grizzly Bears", p);
        gone.addStaticAbility("Mode$ CantRegenerate | ValidCard$ Creature");
        p.getZone(ZoneType.Battlefield).remove(gone); // no card of the game has the mode now; the flag stays
        assertTrue(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));

        Game copy = new GameCopier(game).makeCopy();
        assertTrue(copy.mayHaveStaticAbilityMode(StaticAbilityMode.CantPreventDamage));
        assertTrue(copy.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        assertFalse(copy.mayHaveStaticAbilityMode(StaticAbilityMode.CombatDamageToughness));
        Card copiedBears = copy.findById(bears.getId());
        assertTrue(copiedBears != null && copiedBears != bears);
        assertSame(copiedBears.getGame(), copy);
        long skips = checker.skips.get(); // the copy skips scans of its own
        assertTrue(StaticAbilityCantPreventDamage.cantPreventDamage(copiedBears, true));
        assertFalse(StaticAbilityCantRegenerate.cantRegenerate(copiedBears)); // scanned, finds none
        assertEquals(checker.skips.get(), skips);
        assertFalse(StaticAbilityCombatDamageToughness.combatDamageToughness(copiedBears)); // skipped
        assertEquals(checker.skips.get(), skips + 1);
        assertNoMismatch();
    }

    /** An LKI copy as the attacker (or as the source) with a static ability added to the copy alone. */
    @Test
    public void anLkiCopysOwnStaticAbilityIsFound() {
        Game game = initAndCreateGame();
        Card attacker = addCard("Grizzly Bears", ai(game));
        Card blocker = addCard("Grizzly Bears", opponent(game));
        assertFalse(StaticAbilityCantAttackBlock.cantBlockBy(attacker, blocker));
        assertFalse(StaticAbilityCantPreventDamage.cantPreventDamage(attacker, true));
        assertEquals(checker.skips.get(), 2L);

        Card lki = CardCopyService.getLKICopy(attacker);
        lki.addStaticAbility("Mode$ CantBlockBy | ValidAttacker$ Creature.Self | Description$ unblockable");
        assertTrue(StaticAbilityCantAttackBlock.cantBlockBy(lki, blocker));
        assertFalse(StaticAbilityCantAttackBlock.cantBlockBy(attacker, blocker)); // the copy's static is not the card's

        // a source equal to a card of the zones is not visited itself (the card of the zones is, as the
        // CardCollection's add left it out), so the copy of a hand card, which no zone snapshot holds, is used
        Card inHand = addCardToZone("Grizzly Bears", ai(game), ZoneType.Hand);
        Card lkiSource = CardCopyService.getLKICopy(inHand);
        lkiSource.addStaticAbility("Mode$ CantPreventDamage | ValidSource$ Card.Self | EffectZone$ All | Description$ x");
        assertTrue(StaticAbilityCantPreventDamage.cantPreventDamage(lkiSource, true));
        assertFalse(StaticAbilityCantPreventDamage.cantPreventDamage(inHand, true));
        Card lkiOnBoard = CardCopyService.getLKICopy(attacker);
        lkiOnBoard.addStaticAbility("Mode$ CantPreventDamage | ValidSource$ Card.Self | EffectZone$ All | Description$ y");
        assertFalse(StaticAbilityCantPreventDamage.cantPreventDamage(lkiOnBoard, true)); // the attacker is visited instead
        assertEquals(checker.skips.get(), 2L);
        assertNoMismatch();
    }

    /** A blocker of another game: cantBlockBy scans (and the blocker's static ability is found) whatever the flags. */
    @Test
    public void aBlockerOfAnotherGameIsScanned() {
        Game game = initAndCreateGame();
        Game other = initAndCreateGame();
        Card attacker = addCard("Grizzly Bears", ai(game));
        addCard("Grizzly Bears", ai(other)); // so the blocker's id is not the attacker's (equal cards are visited once)
        addCard("Grizzly Bears", ai(other));
        Card blocker = addCard("Grizzly Bears", opponent(other));
        assertTrue(blocker.getId() != attacker.getId());
        blocker.addStaticAbility("Mode$ CantBlockBy | ValidAttacker$ Creature | Description$ x");
        assertFalse(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantBlockBy));
        assertTrue(StaticAbilityCantAttackBlock.cantBlockBy(attacker, blocker));
        assertEquals(checker.skips.get(), 0L);
        assertNoMismatch();
    }

    /** A card with no game joining one: nothing can be known of its static abilities. */
    @Test
    public void aCardWithoutAGameSaturatesTheGameItJoins() {
        Game game = initAndCreateGame();
        Card loose = createCard("Grizzly Bears", null);
        assertNull(loose.getGame());
        assertFalse(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        loose.dangerouslySetGame(game);
        for (StaticAbilityMode m : StaticAbilityMode.values()) {
            assertTrue(game.mayHaveStaticAbilityMode(m), m.toString());
        }
    }

    /** A card moving from one game to another brings the modes the first game noted. */
    @Test
    public void aCardMovingBetweenGamesBringsItsGamesModes() {
        Game from = initAndCreateGame();
        Game to = initAndCreateGame();
        Card holder = addCard("Grizzly Bears", ai(from));
        holder.addStaticAbility("Mode$ CantRegenerate | ValidCard$ Creature");
        assertFalse(to.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        holder.dangerouslySetGame(to);
        assertTrue(to.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        assertFalse(to.mayHaveStaticAbilityMode(StaticAbilityMode.CantPreventDamage));
    }

    /** A static ability given a host of another game notes its modes there. */
    @Test
    public void aNewHostOfAnotherGameNotesTheModes() {
        Game from = initAndCreateGame();
        Game to = initAndCreateGame();
        Card holder = addCard("Grizzly Bears", ai(from));
        StaticAbility st = holder.addStaticAbility("Mode$ CantRegenerate | ValidCard$ Creature");
        Card newHost = addCard("Grizzly Bears", ai(to));
        assertFalse(to.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        st.setHostCard(newHost);
        assertTrue(to.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        assertFalse(to.mayHaveStaticAbilityMode(StaticAbilityMode.CantPreventDamage));
    }

    /** A card of another game put in this game's zone: this game can no longer tell its modes. */
    @Test
    public void aCardOfAnotherGameInAZoneSaturatesTheGame() {
        Game game = initAndCreateGame();
        Game other = initAndCreateGame();
        Card foreign = createCard("Grizzly Bears", ai(other));
        assertFalse(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        ai(game).getZone(ZoneType.Graveyard).add(foreign);
        assertTrue(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        assertTrue(game.mayHaveStaticAbilityMode(StaticAbilityMode.Devotion));
    }

    /** The same through Zone.setCards, which replaces a zone's cards wholesale. */
    @Test
    public void aCardOfAnotherGameSetIntoAZoneSaturatesTheGame() {
        Game game = initAndCreateGame();
        Game other = initAndCreateGame();
        Card own = createCard("Grizzly Bears", ai(game));
        Card foreign = createCard("Grizzly Bears", ai(other));
        ai(game).getZone(ZoneType.Exile).setCards(List.of(own));
        assertFalse(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate)); // a card of the game: nothing
        ai(game).getZone(ZoneType.Exile).setCards(List.of(own, foreign));
        assertTrue(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        assertTrue(game.mayHaveStaticAbilityMode(StaticAbilityMode.Devotion));
    }

    /** A static ability hosted by a card of another game, put straight into a state of a card of this game. */
    @Test
    public void aStaticAbilityAddedToAStateNotesItsModeInTheStatesGame() {
        Game game = initAndCreateGame();
        Game other = initAndCreateGame();
        Card cardOfB = addCard("Grizzly Bears", ai(other));
        StaticAbility st = StaticAbility.create("Mode$ CantRegenerate | ValidCard$ Creature", cardOfB,
                cardOfB.getCurrentState(), true);
        assertTrue(other.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        Card cardOfA = addCard("Grizzly Bears", ai(game));
        assertFalse(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        cardOfA.getCurrentState().addStaticAbility(st);
        assertTrue(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        assertFalse(game.mayHaveStaticAbilityMode(StaticAbilityMode.Devotion));
    }

    /** The same through a keyword instance whose host is a card of this game. */
    @Test
    public void aStaticAbilityAddedToAKeywordNotesItsModeInTheKeywordHostsGame() {
        Game game = initAndCreateGame();
        Game other = initAndCreateGame();
        Card cardOfB = addCard("Grizzly Bears", ai(other));
        StaticAbility st = StaticAbility.create("Mode$ CantRegenerate | ValidCard$ Creature", cardOfB,
                cardOfB.getCurrentState(), true);
        Card cardOfA = addCard("Grizzly Bears", ai(game));
        KeywordInterface ki = Keyword.getInstance("Vigilance");
        ki.setHostCard(cardOfA); // it has no static ability yet, so this notes nothing
        assertFalse(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        ki.addStaticAbility(st);
        assertTrue(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        assertFalse(game.mayHaveStaticAbilityMode(StaticAbilityMode.Devotion));
    }

    /** A GameSnapshot copy starts with every mode of the game it copies, even one no card of it has any more. */
    @Test
    public void aSnapshotCopyStartsWithTheGamesModes() {
        Game game = initAndCreateGame();
        Player p = ai(game);
        Card bears = addCard("Grizzly Bears", p);
        Card gone = addCard("Grizzly Bears", p);
        gone.addStaticAbility("Mode$ CantRegenerate | ValidCard$ Creature");
        p.getZone(ZoneType.Battlefield).remove(gone);
        assertTrue(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));

        Game copy = new GameSnapshot(game).makeCopy();
        assertTrue(copy != game);
        assertSame(copy.findById(bears.getId()).getGame(), copy);
        assertTrue(copy.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        assertFalse(copy.mayHaveStaticAbilityMode(StaticAbilityMode.CantPreventDamage));
    }

    /** The second and third modes of the guards that test more than one: a lone static of each makes the scan run. */
    @Test
    public void everyModeOfTheLifeGuardsMakesTheScanRun() {
        Game lose = initAndCreateGame();
        addCard("Grizzly Bears", ai(lose)).addStaticAbility("Mode$ CantChangeLife | ValidPlayer$ Player");
        assertTrue(StaticAbilityCantGainLosePayLife.anyCantLoseLife(ai(lose)));
        assertTrue(StaticAbilityCantGainLosePayLife.anyCantGainLife(ai(lose)));
        assertTrue(StaticAbilityCantGainLosePayLife.anyCantPayLife(ai(lose), false, null));

        Game pay = initAndCreateGame();
        addCard("Grizzly Bears", ai(pay)).addStaticAbility("Mode$ CantLoseLife | ValidPlayer$ Player");
        assertTrue(StaticAbilityCantGainLosePayLife.anyCantPayLife(ai(pay), false, null));
        assertTrue(StaticAbilityCantGainLosePayLife.anyCantLoseLife(ai(pay)));
        assertFalse(StaticAbilityCantGainLosePayLife.anyCantGainLife(ai(pay))); // skipped
        assertEquals(checker.skips.get(), 1L);
        assertNoMismatch();
    }

    /** A static ability without a mode keeps every scan, so the scan still fails on it as it did. */
    @Test
    public void aStaticAbilityWithoutAModeKeepsTheScan() {
        Game game = initAndCreateGame();
        Card bears = addCard("Grizzly Bears", ai(game));
        Card odd = addCard("Grizzly Bears", ai(game));
        // one cannot be made from a script (its layers are worked out from its modes)...
        expectThrows(NullPointerException.class, () -> odd.addStaticAbility("Description$ no mode"));
        assertFalse(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        // ...but setMode takes null
        StaticAbility modeless = odd.addStaticAbility("Mode$ Devotion | ValidPlayer$ Player.Opponent");
        assertFalse(game.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        modeless.setMode(null);
        assertNull(modeless.getMode());
        for (StaticAbilityMode m : StaticAbilityMode.values()) {
            assertTrue(game.mayHaveStaticAbilityMode(m), m.toString());
        }
        expectThrows(NullPointerException.class, () -> StaticAbilityCantRegenerate.cantRegenerate(bears));
        assertEquals(checker.skips.get(), 0L);
    }

    /** Modes written into a copy keep their own set; copying notes the copy's modes in its host's game. */
    @Test
    public void aCopyNotesItsModesInItsHostsGame() {
        Game from = initAndCreateGame();
        Game to = initAndCreateGame();
        Card holder = addCard("Grizzly Bears", ai(from));
        StaticAbility st = holder.addStaticAbility("Mode$ CantRegenerate,Devotion | ValidCard$ Creature");
        assertEquals(st.getMode(), EnumSet.of(StaticAbilityMode.CantRegenerate, StaticAbilityMode.Devotion));
        Card newHost = addCard("Grizzly Bears", ai(to));
        StaticAbility copy = st.copy(newHost, false);
        assertTrue(copy.getMode() != st.getMode());
        assertTrue(to.mayHaveStaticAbilityMode(StaticAbilityMode.CantRegenerate));
        assertTrue(to.mayHaveStaticAbilityMode(StaticAbilityMode.Devotion));
        assertFalse(to.mayHaveStaticAbilityMode(StaticAbilityMode.CantPreventDamage));
    }

    /**
     * The five scans that no longer build a CardCollection visit, for attackers, blockers and sources on the board,
     * in other zones and as LKI copies (equal to a board card, a different object), the cards that collection held,
     * in its order: all of them when nothing matches, the ones before the match when something does. The board's
     * static abilities have every mode with conditions nothing meets, so no scan is skipped; one board card's do match.
     */
    @Test
    public void theDirectScansVisitTheCollectionsOrder() {
        for (long seed = 1; seed <= 6; seed++) {
            visitOrderRound(seed);
        }
        assertTrue(checker.visitCalls.get() >= 6 * 6 * 9, checker.summary());
        assertTrue(checker.visitCallsWithMatch.get() > 0, checker.summary());
        assertEquals(checker.skips.get(), 0L, checker.summary());
        assertNoMismatch();
    }

    private void visitOrderRound(long seed) {
        Random r = new Random(seed);
        Game game = initAndCreateGame();
        Player p = ai(game), o = opponent(game);
        ZoneType[] zones = {ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Hand, ZoneType.Library};
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Player owner = r.nextBoolean() ? p : o;
            Card c = addCardToZone("Grizzly Bears", owner, zones[r.nextInt(zones.length)]);
            c.addStaticAbility("Mode$ CantBlockBy | ValidAttacker$ Card.namedNoSuchCard | EffectZone$ All");
            c.addStaticAbility("Mode$ CantBlock | ValidCard$ Card.namedNoSuchCard | EffectZone$ All");
            c.addStaticAbility("Mode$ CantPreventDamage | ValidSource$ Card.namedNoSuchCard | EffectZone$ All");
            c.addStaticAbility("Mode$ AssignNoCombatDamage | ValidCard$ Card.namedNoSuchCard | EffectZone$ All");
            c.addStaticAbility("Mode$ AlternativeCost | ValidCard$ Card.namedNoSuchCard | Cost$ 0 | EffectZone$ All");
            cards.add(c);
        }
        Card matching = addCardToZone("Grizzly Bears", o, zones[r.nextInt(3)]);
        matching.addStaticAbility("Mode$ CantBlock | ValidCard$ Card.namedMatch_Me | EffectZone$ All");
        matching.addStaticAbility("Mode$ CantPreventDamage | ValidSource$ Card.namedMatch_Me | EffectZone$ All");
        matching.addStaticAbility("Mode$ AssignNoCombatDamage | ValidCard$ Card.namedMatch_Me | EffectZone$ All");
        matching.addStaticAbility("Mode$ CantBlockBy | ValidAttacker$ Card.namedMatch_Me | EffectZone$ All");
        Card named = addCardToZone("Grizzly Bears", o, ZoneType.Battlefield);
        named.setName("Match Me");
        cards.add(named);

        // who takes part: board cards, LKI copies of them (equal, not the same object), and a hand card
        List<Card> parties = new ArrayList<>();
        parties.add(cards.get(r.nextInt(cards.size())));
        parties.add(CardCopyService.getLKICopy(cards.get(r.nextInt(cards.size()))));
        parties.add(named);
        parties.add(CardCopyService.getLKICopy(named));
        parties.add(addCardToZone("Grizzly Bears", p, ZoneType.Hand));
        parties.add(matching);
        List<Card> blockers = new ArrayList<>(parties);
        blockers.add(null);
        for (Card a : parties) {
            SpellAbility spell = a.getFirstSpellAbility();
            StaticAbilityCantPreventDamage.cantPreventDamage(a, r.nextBoolean());
            StaticAbilityAssignNoCombatDamage.assignNoCombatDamage(a);
            StaticAbilityCantAttackBlock.cantBlock(a);
            if (spell != null) {
                StaticAbilityAlternativeCost.alternativeCosts(spell, a, p);
            }
            for (Card b : blockers) {
                StaticAbilityCantAttackBlock.cantBlockBy(a, b);
            }
        }
    }
}
