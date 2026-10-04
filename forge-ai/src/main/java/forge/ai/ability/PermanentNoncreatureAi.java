package forge.ai.ability;

import forge.ai.AiAbilityDecision;
import forge.ai.AiPlayDecision;
import forge.ai.ComputerUtilAbility;
import forge.ai.SpecialCardAi;
import forge.game.Game;
import forge.game.ability.AbilityFactory;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** 
 * AbilityFactory for Creature Spells.
 *
 */
public class PermanentNoncreatureAi extends PermanentAi {

    // Improbable Alliance is judged here rather than in checkApiLogic. canPlay is reached only
    // from canPlayWithSubs: the owner's cast from hand (or from Wrenn's Resolve exile) and a
    // MayPlay thief, the path AI:RemoveDeck:All used to block. PermanentAi.doTriggerNoCost, which
    // canPlayFromEffectAI uses for Play effects (Nathan Drake's exile-and-cast), goes straight to
    // checkApiLogic; that path never read the hint, so it stays stock. The hook runs only after
    // the stock checks (restrictions, the main-2 wait, checkApiLogic, willPayCosts) approved.
    //
    // Misleading Signpost dropped AI:RemoveDeck:All. It has Flash, so the stock approval below
    // would cast it at almost every priority outside our own Main 1; its window (an end step
    // before our own turn, empty stack, payable by G2) is judged first, before super.canPlay and
    // before canPlaySa's canPayCost, so every decline draws nothing. Reached via
    // AiController.canPlaySa (the cast path and its other public callers); a Play-effect cast
    // (Nathan Drake's attack trigger) never comes here and keeps A's stock approval.
    @Override
    protected AiAbilityDecision canPlay(final Player ai, final SpellAbility sa) {
        if (sa.isSpell() && SpecialCardAi.MisleadingSignpost.NAME.equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            final AiAbilityDecision window = SpecialCardAi.MisleadingSignpost.consider(ai, sa);
            if (!window.willingToPlay()) {
                return window;
            }
        }
        final AiAbilityDecision decision = super.canPlay(ai, sa);
        if (decision.willingToPlay() && sa.isSpell()
                && SpecialCardAi.ImprobableAlliance.NAME.equals(ComputerUtilAbility.getAbilitySourceName(sa))) {
            final AiAbilityDecision alliance = SpecialCardAi.ImprobableAlliance.considerCast(ai, sa);
            if (!alliance.willingToPlay()) {
                return alliance;
            }
        }
        return decision;
    }

    /**
     * The rest of the logic not covered by the canPlayAI template is defined
     * here
     */
    @Override
    protected AiAbilityDecision checkApiLogic(final Player ai, final SpellAbility sa) {
        AiAbilityDecision decision = super.checkApiLogic(ai, sa);
        if (!decision.willingToPlay()) {
            return decision;
        }

        final Card host = sa.getHostCard();
        final String sourceName = ComputerUtilAbility.getAbilitySourceName(sa);
        final Game game = ai.getGame();

        // Manascape Refractor keeps AI:RemoveDeck:All; the G3 dispatcher lets only its owner's hand
        // cast through, and that cast is judged here (reached in Main 2 only, PermanentAi:38).
        if (SpecialCardAi.ManascapeRefractor.isOwnHandCast(sa)) {
            return SpecialCardAi.ManascapeRefractor.consider(ai, sa);
        }

        // Lavabrink Floodgates dropped AI:RemoveDeck:All. Its own cast is judged here, after the
        // stock approval (own Main 2 in practice, PermanentAi.checkPhaseRestrictions); a Play-effect
        // cast (Etali's free cast, Nathan Drake's attack trigger) keeps the stock answer, as in A.
        if (SpecialCardAi.LavabrinkFloodgates.NAME.equals(host.getName()) && sa.isSpell()
                && !sa.isCastFromPlayEffect()) {
            return SpecialCardAi.LavabrinkFloodgates.consider(ai, sa);
        }

        // Act of Authority dropped AI:RemoveDeck:All. Its ETB exile is optional, so checkETBEffects
        // skips it (AiController:326) and the stock approval above would cast it into an empty
        // board: cast only when the ETB will exile a worthy opposing card, judged RNG-free. A
        // thief's Play-effect cast (PermanentAi.doTriggerNoCost) is judged by the same floor.
        if (SpecialCardAi.ActOfAuthority.NAME.equals(sourceName)) {
            return SpecialCardAi.ActOfAuthority.considerCast(ai, sa);
        }

        // Carpet of Flowers dropped AI:RemoveDeck:All. A paid cast (hand, or Yasmin Khan's / The
        // Flux's may-play from exile) only against Islands its trigger can count; a free cascade
        // cast (isCastFromPlayEffect) keeps the stock WillPlay, as in A (SpecialCardAi.CascadeShell
        // counts on it). Draws no random numbers.
        if (SpecialCardAi.CarpetOfFlowers.NAME.equals(sourceName) && !sa.isCastFromPlayEffect()) {
            return SpecialCardAi.CarpetOfFlowers.considerCast(ai, sa);
        }

        // Acorn Catapult dropped AI:RemoveDeck:All. A paid cast (from hand, or a MayPlay thief's) is
        // screened for affordability here, drawing no random numbers, before canPlaySa's LKI copy and
        // canPayCost, whose test payment rolls isManaSourceReserved. The isCastFromPlayEffect guard is
        // load-bearing: PermanentAi.doTriggerNoCost ends in this checkApiLogic, and in A Nathan
        // Drake's attack-trigger cast (PlayAi.chooseSingleCard -> canPlayFromEffectAI, :210) took the
        // stock WillPlay and then rolled ComputerUtilCost.canPayCost (PlayAi:214) even when it could
        // not pay; screening that path would decline before the roll and desync those games, so it
        // stays stock.
        if (SpecialCardAi.AcornCatapult.NAME.equals(sourceName) && !sa.isCastFromPlayEffect()) {
            final AiAbilityDecision cast = SpecialCardAi.AcornCatapult.considerCast(ai, sa);
            if (!cast.willingToPlay()) {
                return cast;
            }
        }

        // Netherborn Altar dropped AI:RemoveDeck:All. Behind the stock approval it would be cast as a
        // blank in any Main 2: paid casts (from hand, or a MayPlay thief's) only once a commander of
        // ours has been taxed, at a life that can pay one activation, into a window real mana can
        // pay for. Every decline draws no random numbers and returns before canPlaySa's canPayCost
        // test payment. A Play-effect cast (Nathan Drake's attack trigger casts it from our
        // library; PermanentAi.doTriggerNoCost ends in this checkApiLogic) keeps the stock answer
        // it had while the hint was on the card: the hint never applied there.
        if (SpecialCardAi.NetherbornAltar.NAME.equals(sourceName) && !sa.isCastFromPlayEffect()) {
            final AiAbilityDecision cast = SpecialCardAi.NetherbornAltar.considerCast(ai, sa);
            if (!cast.willingToPlay()) {
                return cast;
            }
        }

        // Manifold Key dropped AI:RemoveDeck:All. Behind the stock approval its {1} was paid with
        // whatever Main 2 had left, and the stock payer reaches a sacrifice source last but does
        // reach it (Phyrexian Altar sacrificed a Hullbreaker Horror for it): paid casts (from hand,
        // or a MayPlay thief's) only when untapped sources whose mana spends nothing they cannot
        // use again (no sacrifice, no mana) cover the cost. Every decline draws no random numbers
        // and returns before canPlaySa's canPayCost test payment. A Play-effect cast (Nathan
        // Drake's attack trigger, Call Forth the Tempest's cascade; PermanentAi.doTriggerNoCost
        // ends in this checkApiLogic) keeps the stock answer it had while the hint was on the
        // card: the hint never applied there.
        if (sa.isSpell() && SpecialCardAi.ManifoldKey.NAME.equals(sourceName) && !sa.isCastFromPlayEffect()) {
            final AiAbilityDecision cast = SpecialCardAi.ManifoldKey.considerCast(ai, sa);
            if (!cast.willingToPlay()) {
                return cast;
            }
        }

        // Night Soil dropped AI:RemoveDeck:All. Behind the stock approval it would be cast as a
        // do-nothing {G}{G} in any Main 2: cast only while some graveyard holds a pair its
        // activation would pay with right now (the chooser its activation and AiCostDecision
        // use). A paid cast (from hand, or a MayPlay thief's) is screened by G2 first; a
        // Play-effect cast (Nathan Drake's attack trigger) meets the pair floor alone, judged for
        // the thief. Every decline draws no random numbers.
        if (sa.isSpell() && SpecialCardAi.NightSoil.NAME.equals(sourceName)) {
            final AiAbilityDecision soil = SpecialCardAi.NightSoil.considerCast(ai, sa);
            if (!soil.willingToPlay()) {
                return soil;
            }
        }

        // Gideon, Champion of Justice dropped AI:RemoveDeck:All. Its own cast is judged here, after
        // the stock approval (Main 2 unless castPermanentInMain1): only into a window real mana can
        // pay for, so a decline draws no random numbers and returns before canPlaySa's canPayCost
        // test payment. A Play-effect cast (Nathan Drake's attack trigger, Rashmi and Ragavan's
        // free cast; PermanentAi.doTriggerNoCost ends in this checkApiLogic) keeps the stock answer
        // it had while the hint was on the card: the hint never applied there.
        if (sa.isSpell() && !sa.isCastFromPlayEffect()
                && SpecialCardAi.GideonChampionOfJustice.NAME.equals(host.getName())) {
            return SpecialCardAi.GideonChampionOfJustice.considerCast(ai, sa);
        }

        // Check for valid targets before casting
        if (host.hasSVar("OblivionRing")) {
            // TODO: only the "may" case wouldn't fail checkETBEffects - replace with NeedsToPlay SVar?
            SpellAbility effectExile = AbilityFactory.getAbility(host.getSVar("TrigExile"), host);
            final ZoneType origin = ZoneType.listValueOf(effectExile.getParamOrDefault("Origin", "Battlefield")).get(0);
            effectExile.setActivatingPlayer(ai);
            CardCollection targets = CardLists.getTargetableCards(game.getCardsIn(origin), effectExile);
            if (sourceName.equals("Suspension Field")
                    || sourceName.equals("Detention Sphere")) {
                // existing "exile until leaves" enchantments only target opponent's permanents
                targets = CardLists.filterControlledBy(targets, ai.getOpponents());
            }
            if (targets.isEmpty()) {
                return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
            }
        }
        return decision;
    }
}
