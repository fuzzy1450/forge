package forge.ai.combo;

/**
 * The kinds of cycle piece 2's loop driver runs: Kinnan, Bonder Prodigy's unlimited-mana loops (spec
 * 2026-10-29-combo-aware-ai-loops-design.md section 6.1). A cycle is one turn of the loop itself, which makes mana.
 * Each kind records how fast its cycle runs (section 6.6) and whether the driver can end the loop whenever it chooses
 * (section 6.2); {@link LoopRows} says which table row is which kind.
 */
public enum LoopKind {
    /** Tap a Monolith for mana, then pay its untap: Kinnan's bonus mana and Nyxbloom Ancient's tripling are the net. A
     *  mana ability and an activation, so instant speed. */
    ROCK_UNTAP(true, true),
    /** Flicker Palinchron with the {1}{U} Deadeye Navigator grants it; Palinchron's ETB untaps up to seven of our
     *  tapped lands, and Deadeye pairs with it again. An activation, so instant speed. */
    FLICKER(true, true),
    /** Return Palinchron to our hand with its own {2}{U}{U}, then cast it again, with Nyxbloom Ancient tripling our
     *  lands; its ETB untaps up to seven of them. A creature cast, so sorcery speed. */
    RECAST(false, true),
    /** Cast one piece; Hullbreaker Horror's cast trigger returns another, already tapped, to our hand; tap the new one.
     *  An artifact cast, so sorcery speed. */
    BOUNCE(false, true);

    private final boolean instantSpeed;
    private final boolean canStop;

    LoopKind(final boolean instantSpeed, final boolean canStop) {
        this.instantSpeed = instantSpeed;
        this.canStop = canStop;
    }

    /** Whether a cycle runs at instant speed, so the driver can go off in response to removal (section 6.6): the rock
     *  untaps and the flicker. The recast and the bounce need a sorcery-speed cast. */
    public boolean instantSpeed() {
        return instantSpeed;
    }

    /** Whether the driver can end the loop whenever it chooses, the check before it starts one (section 6.2): every
     *  step that repeats is optional, an activation, a cast, or a "may" or "up to" choice. True for all four kinds (R4
     *  section Q3): their mandatory steps are fixed mana bonuses (Kinnan, Nyxbloom) or triggers whose effect may be
     *  none (Palinchron's "up to seven", Hullbreaker's "choose up to one", Deadeye's "you may pair"). */
    public boolean canStop() {
        return canStop;
    }
}
