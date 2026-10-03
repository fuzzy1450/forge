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
    @Override
    protected AiAbilityDecision canPlay(final Player ai, final SpellAbility sa) {
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
