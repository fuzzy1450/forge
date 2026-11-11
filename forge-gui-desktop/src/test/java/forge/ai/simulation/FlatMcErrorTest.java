package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

import java.util.Map;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.IHasSVars;
import forge.game.ability.AbilityFactory;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.util.GameAbandoned;
import forge.util.SimScope;
import forge.util.UnscopedAccessError;

/**
 * Errors are never decisions (MTG_DeckMaker's tools/combo-ai/flat-mc/SPEC.md, section 4.5). Under a simulation, a
 * scope bound to the thread or strict mode on (as every harness JVM boots), an Error raised while AbilityFactory builds
 * an ability comes out unwrapped, and FlatMonteCarloPicker.reportFailure rethrows an Error in its failure's chain. So
 * no RuntimeException catch reads either as a failed line or as nothing to play. Unbound and not strict, the GUI's
 * case, both behave as before.
 */
public class FlatMcErrorTest extends AITest {
    /** Opt's draw. Its SpellAbility draws an id, a scope's seam, inside AbilityFactory's try, not before it. */
    private static final String DRAW = "SP$ Draw | NumCards$ 1";

    private Game game;

    @BeforeMethod
    public void newGame() {
        game = initAndCreateGame();
    }

    @Test
    public void anAbilityErrorComesOutUnwrappedUnderACancelledScope() {
        final Card c = addCardToZone("Opt", game.getPlayers().get(1), ZoneType.Hand);
        final SimScope s = new SimScope(5L);
        s.cancel();
        final Throwable t = Scoped.thrownBy(s, () -> AbilityFactory.getAbility(DRAW, c));
        assertEquals(t.getClass(), GameAbandoned.class);
    }

    @Test
    public void anUnboundStrictRefusalComesOutUnwrapped() {
        final Card c = addCardToZone("Opt", game.getPlayers().get(1), ZoneType.Hand);
        SimScope.setStrict(true);
        try {
            assertThrows(UnscopedAccessError.class, () -> AbilityFactory.getAbility(DRAW, c));
        } finally {
            SimScope.setStrict(false);
        }
    }

    @Test
    public void unboundAndNotStrictAnAbilityIsBuiltAsBefore() {
        final Card c = addCardToZone("Opt", game.getPlayers().get(1), ZoneType.Hand);
        final SpellAbility sa = AbilityFactory.getAbility(DRAW, c);
        assertNotNull(sa);
        assertEquals(sa.getApi(), ApiType.Draw);
        assertSame(sa.getHostCard(), c);
    }

    /**
     * The GUI's case keeps AbilityFactory's wrapper: unbound and not strict, an Error raised while an ability is built
     * comes out inside a RuntimeException naming the card, as it always has. Here the lookup of the ability's
     * sub-ability raises it.
     */
    @Test
    public void unboundAndNotStrictAnAbilityErrorIsStillWrapped() {
        final Card c = addCardToZone("Opt", game.getPlayers().get(1), ZoneType.Hand);
        final StackOverflowError deep = new StackOverflowError();
        final RuntimeException t = expectThrows(RuntimeException.class,
                () -> AbilityFactory.getAbility(DRAW + " | SubAbility$ DBDraw", c, new ThrowingSVars(deep)));
        assertEquals(t.getClass(), RuntimeException.class, "AbilityFactory's own wrapper");
        assertSame(t.getCause(), deep);
    }

    /**
     * The four catches that funnel into reportFailure hand it the failure, the SOE/OOM arm wrapping its Error as here.
     * A simulation is a bound scope or strict mode. The rethrow comes first, so a rethrown Error is never counted, or
     * printed, as a rollout that fell back to the heuristic choice.
     */
    @Test
    public void aRolloutErrorIsRethrownOnlyUnderASimulation() {
        final RuntimeException wrapped = new RuntimeException(new StackOverflowError());
        final int before = FlatMonteCarloPicker.getTotalFailures();
        Reflect.callStatic(FlatMonteCarloPicker.class, "reportFailure", wrapped); // unbound, not strict: returns
        assertEquals(FlatMonteCarloPicker.getTotalFailures(), before + 1, "and is reported as before");
        final Throwable bound = Scoped.thrownBy(new SimScope(6L),
                () -> Reflect.callStatic(FlatMonteCarloPicker.class, "reportFailure", wrapped));
        assertNotNull(bound, "under a bound scope, reportFailure returned");
        assertEquals(bound.getClass(), StackOverflowError.class);
        SimScope.setStrict(true);
        try {
            final Throwable strict = Reflect.thrownBy(FlatMonteCarloPicker.class, "reportFailure", wrapped);
            assertNotNull(strict, "unbound under strict mode, as a harness JVM runs, reportFailure returned");
            assertEquals(strict.getClass(), StackOverflowError.class);
        } finally {
            SimScope.setStrict(false);
        }
        assertEquals(FlatMonteCarloPicker.getTotalFailures(), before + 1, "a rethrown Error is never reported");
    }

    /** A failure with no Error in its chain is still reported under a bound scope: the line is invalid and counted. */
    @Test
    public void anOrdinaryRolloutFailureIsStillReportedUnderAScope() {
        final int before = FlatMonteCarloPicker.getTotalFailures();
        final Throwable t = Scoped.thrownBy(new SimScope(7L), () -> Reflect.callStatic(FlatMonteCarloPicker.class,
                "reportFailure", new IllegalStateException("an ordinary failure")));
        assertNull(t, "an ordinary failure is reported, not rethrown");
        assertEquals(FlatMonteCarloPicker.getTotalFailures(), before + 1);
    }

    /** SVars whose lookup throws {@code error}: an Error raised inside AbilityFactory's try. */
    private static final class ThrowingSVars implements IHasSVars {
        private final Error error;

        ThrowingSVars(final Error error) {
            this.error = error;
        }

        @Override
        public boolean hasSVar(final String name) {
            throw error;
        }

        @Override
        public String getSVar(final String name) {
            throw error;
        }

        @Override
        public void setSVar(final String name, final String value) {
        }

        @Override
        public void setSVars(final Map<String, String> newSVars) {
        }

        @Override
        public Map<String, String> getSVars() {
            return Map.of();
        }

        @Override
        public void removeSVar(final String var) {
        }
    }
}
