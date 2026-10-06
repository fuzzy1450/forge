package forge.sim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.google.common.eventbus.Subscribe;

import forge.game.Game;
import forge.game.GameEndReason;
import forge.game.GameOutcome;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventGameStarted;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.player.PlayerOutcome;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;

/**
 * Each game's record and the job's summary, ported line for line from MTG_DeckMaker's sim/java/mtgsim/Harness.java
 * (CardStat, GameStats, record, fallbackRecord, Json): the same events, the same keys in the same order, the same win
 * pair. A {@link Stats} is created with the Game in {@link GameRunner#play}'s observer and subscribed there, where the
 * harness called subscribeToEvents. Design: MTG_DeckMaker's docs/superpowers/specs/2026-10-06-harness-in-engine-design.md,
 * sections 3.3 and 5.
 */
public final class GameRecords {
    /** The end reasons the summary counts, in its order (Harness.run's). */
    public static final String[] END_REASONS = {"AllOpponentsLost", "WinsGameSpellEffect", "Draw", "Timeout", "Error"};

    private GameRecords() { }

    // ------------------------------------------------------------- statistics

    static final class CardStat {
        int inOpeningHand, timesCast, died;
        Integer firstDrawnTurn, firstCastTurn;
        String finalZone;
        // Casts and land drops by the card's OWNER, and the turn of the first one
        // (owner_casts, owner_first_cast_turn). timesCast and firstCastTurn count
        // every caster: an opponent who casts this card through a Play effect
        // (Nathan Drake, Jeleva) bumps those two and not these.
        int ownerCasts;
        Integer firstOwnerCastTurn;
    }

    /** Event-bus subscriber: per-seat counters and per-card summaries for one game. */
    public static final class Stats {
        final Game game;
        final int nSeats;
        final Map<Integer, Integer> cardSeat = new HashMap<>();   // CardView id -> seat
        final Map<Integer, String> cardName = new HashMap<>();    // CardView id -> paper name
        final Set<Integer> commanderIds = new java.util.HashSet<>();
        final List<Map<String, CardStat>> cards = new ArrayList<>();
        final int[] mulligans, landsPlayed, spellsCast, commanderCasts;
        final Integer[] eliminatedTurn;
        Integer firstSeat = null;
        int turn = 0;

        public Stats(Game game, int nSeats) {
            this.game = game;
            this.nSeats = nSeats;
            mulligans = new int[nSeats]; landsPlayed = new int[nSeats];
            spellsCast = new int[nSeats]; commanderCasts = new int[nSeats];
            eliminatedTurn = new Integer[nSeats];
            for (int i = 0; i < nSeats; i++) cards.add(new TreeMap<>());
        }

        /** A Stats with no game: JobRunner's, for a game whose observer never ran. {@link GameRecords#record} reads it
         *  as it reads a timed-out game's, touching no live state. */
        Stats(int nSeats) {
            this(null, nSeats);
        }

        static int seatOf(String playerName) {
            return GameRunner.seatOf(playerName);
        }

        int seatOf(PlayerView pv) { return pv == null ? -1 : seatOf(pv.getName()); }

        void track(Card c, int seat, boolean commander) {
            // Engine-made objects (the Commander effect card, tokens) have no paper
            // card; they are not deck cards, so they stay untracked. Guava's EventBus
            // only logs a subscriber exception, so an NPE here would silently drop
            // every card after it.
            if (c.getPaperCard() == null) return;
            int id = c.getView().getId();
            cardName.put(id, c.getPaperCard().getName());
            cardSeat.put(id, seat);
            if (commander) commanderIds.add(id);
        }

        CardStat stat(int seat, String name) {
            return cards.get(seat).computeIfAbsent(name, k -> new CardStat());
        }

        @Subscribe
        public void onStarted(GameEventGameStarted ev) {
            firstSeat = seatOf(ev.firstTurn());
            for (Player p : game.getRegisteredPlayers()) {
                int seat = seatOf(p.getName());
                if (seat < 0) continue;
                for (Card c : p.getCardsIn(ZoneType.Command)) track(c, seat, true);
                for (Card c : p.getCardsIn(ZoneType.Library)) track(c, seat, false);
                for (Card c : p.getCardsIn(ZoneType.Hand)) track(c, seat, false);
            }
        }

        @Subscribe
        public void onTurn(GameEventTurnBegan ev) {
            // players who lost during the turn that just ended
            for (Player p : game.getRegisteredPlayers()) {
                int seat = seatOf(p.getName());
                PlayerOutcome o = p.getOutcome();
                if (seat >= 0 && o != null && !o.hasWon() && eliminatedTurn[seat] == null) {
                    eliminatedTurn[seat] = Math.max(turn, 1);
                }
            }
            turn = ev.turnNumber();
            if (turn == 1) {
                for (Player p : game.getRegisteredPlayers()) {
                    int seat = seatOf(p.getName());
                    if (seat < 0) continue;
                    for (Card c : p.getCardsIn(ZoneType.Hand)) {
                        int id = c.getView().getId();
                        if (!cardName.containsKey(id)) track(c, seat, false);
                        String name = cardName.get(id);
                        if (name == null) continue; // no paper card: not a deck card
                        CardStat s = stat(seat, name);
                        s.inOpeningHand++;
                        s.firstDrawnTurn = 0;
                    }
                }
            }
        }

        @Subscribe
        public void onMulligan(GameEventMulligan ev) {
            int seat = seatOf(ev.player());
            if (seat >= 0) mulligans[seat]++;
        }

        @Subscribe
        public void onLand(GameEventLandPlayed ev) {
            int seat = seatOf(ev.player());
            if (seat >= 0) landsPlayed[seat]++;
            // The land drop's maker, who is not always the land's owner: a Play
            // effect can put an opponent's land onto the battlefield as a land drop.
            used(ev.land(), seat);
        }

        @Subscribe
        public void onCast(GameEventSpellAbilityCast ev) {
            if (ev.sa() == null || !ev.sa().isSpell()) return;
            CardView host = ev.sa().getHostCard();
            if (host == null) return;
            Integer seat = cardSeat.get(host.getId());
            if (seat == null) return; // token copy / unknown object
            // spells_cast and commander_casts stay credited to the card's owner (spec
            // 2026-09-27 section 5.1); only the card row learns who really cast it.
            spellsCast[seat]++;
            if (commanderIds.contains(host.getId())) commanderCasts[seat]++;
            // The caster is the stack item's activating player: SpellAbilityView has
            // no such accessor, StackItemView has had one since 2.0.14. A null stack
            // item or player gives seatOf -1, which used() counts as the owner.
            used(host, seatOf(ev.si() == null ? null : ev.si().getActivatingPlayer()));
        }

        // caster: the seat that cast the spell or made the land drop. -1 means the
        // engine did not say, and counts as the owner -- the attribution every
        // harness made before owner_casts existed.
        void used(CardView cv, int caster) {
            if (cv == null) return;
            Integer seat = cardSeat.get(cv.getId());
            if (seat == null) return;
            CardStat s = stat(seat, cardName.get(cv.getId()));
            s.timesCast++;
            if (s.firstCastTurn == null) s.firstCastTurn = turn;
            if (caster < 0 || caster == seat) {
                s.ownerCasts++;
                if (s.firstOwnerCastTurn == null) s.firstOwnerCastTurn = turn;
            }
        }

        @Subscribe
        public void onZone(GameEventCardChangeZone ev) {
            // Nothing before turn 1 counts. GameAction.startGame fires GameEventGameStarted,
            // then draws and runs MulliganService, and only then starts turn 1 — so every
            // pre-game draw, including the hands a player mulliganed away, arrives here
            // while turn == 0. Recording those would bias first_drawn_turn by the very
            // thing being measured, and a card seen only in a hand that went back must
            // leave no row at all. onTurn's turn-1 snapshot stamps the hand that was kept.
            if (turn < 1) return;
            if (ev.card() == null) return;
            Integer seat = cardSeat.get(ev.card().getId());
            if (seat == null) return;
            ZoneType from = ev.from() == null ? null : ev.from().zoneType();
            ZoneType to = ev.to() == null ? null : ev.to().zoneType();
            // Spec §5: a row for every card that left the library during the game, by
            // whatever route — drawn, milled, tutored straight onto the battlefield,
            // bounced — so that its final_zone gets reported.
            CardStat s = stat(seat, cardName.get(ev.card().getId()));
            if (from == ZoneType.Library && to == ZoneType.Hand && s.firstDrawnTurn == null) {
                s.firstDrawnTurn = turn;
            }
            if (from == ZoneType.Battlefield && to == ZoneType.Graveyard) {
                s.died++;
            }
        }

        /**
         * The commander whose combat damage to `loser` reached 21: the one with the most
         * if two did (partners), ties broken by name. Named as the card rows name it --
         * the paper name tracked at the start -- else Forge's own name.
         */
        String commanderAt21(Player loser) {
            String best = null;
            int bestDamage = 0;
            for (Map.Entry<Card, Integer> e : loser.getCommanderDamage()) {
                int damage = e.getValue();
                if (damage < 21) continue;
                String name = cardName.get(e.getKey().getView().getId());
                if (name == null) name = e.getKey().getName();
                if (best == null || damage > bestDamage || (damage == bestDamage && name.compareTo(best) < 0)) {
                    best = name;
                    bestDamage = damage;
                }
            }
            return best;
        }
    }

    /** Harness.GameStats.record(): the game's record, its keys in the harness's order. {@code specs} are the job's
     *  seats (their deck_hash, profile and ai). */
    public static Map<String, Object> record(Stats stats, int g, long seed, List<JobFile.Seat> specs,
                                             boolean timedOut, String error, long ms) {
        // After a timeout the game thread is still running: TimeLimitedCodeBlock only
        // interrupts it, and Forge's game loop is compute-bound and never checks. So
        // on that path we report what the subscriber already collected and touch no
        // live engine state — no getOutcome(), no getRegisteredPlayers(), no
        // getCardsIn(). final_life and final_zone stay null for a timed-out game.
        // A Stats with no game (the observer never ran) is read the same way.
        boolean live = !timedOut && stats.game != null;
        GameOutcome out = live ? stats.game.getOutcome() : null;
        Integer winner = null;
        String reason;
        if (timedOut) {
            reason = "Timeout";
        } else if (error != null) {
            reason = "Error";
        } else if (out == null) {
            reason = "Error";
            error = "game ended without an outcome";
        } else if (out.isDraw()) {
            reason = "Draw";
        } else {
            int w = GameRunner.seatOf(out.getWinningPlayer().getPlayer().getName());
            if (w < 0) {
                reason = "Error";
                error = "winner is not one of the seats";
            } else {
                winner = w;
                reason = out.getWinCondition() == GameEndReason.WinsGameSpellEffect ? "WinsGameSpellEffect" : "AllOpponentsLost";
            }
        }
        int lastTurn = out != null ? out.getLastTurnNumber() : stats.turn;
        // final life / final zones / late eliminations — live state, so not after a timeout
        Player[] bySeat = new Player[stats.nSeats];
        if (live) {
            for (Player p : stats.game.getRegisteredPlayers()) {
                int seat = Stats.seatOf(p.getName());
                if (seat >= 0 && seat < stats.nSeats) bySeat[seat] = p;
            }
            for (int seat = 0; seat < stats.nSeats; seat++) {
                Player p = bySeat[seat];
                if (p == null) continue;
                PlayerOutcome o = p.getOutcome();
                if (o != null && !o.hasWon() && stats.eliminatedTurn[seat] == null && winner != null) {
                    stats.eliminatedTurn[seat] = lastTurn;
                }
                for (ZoneType zt : ZoneType.values()) {
                    for (Card c : p.getCardsIn(zt)) {
                        String name = stats.cardName.get(c.getView().getId());
                        Map<String, CardStat> mine = stats.cards.get(seat);
                        if (name != null && mine.containsKey(name)) mine.get(name).finalZone = zt.name();
                    }
                }
            }
        }
        // How the loser lost (games.win_reason / win_card, spec
        // docs/superpowers/specs/2026-10-04-win-reasons-design.md section 2): the
        // losing seat's GameLossReason, verbatim, and the card Forge names for it.
        // Decided two-seat games only; null otherwise. Read after the game, like
        // final_life, and never on the timeout path. Compared as strings, never by a
        // switch on the loss state: javac compiles a switch over any enumeration, one
        // imported from Forge included, into a synthetic Harness$1.class, an eighth
        // class file the farm refuses to serve and every volunteer's pin rejects.
        String winReason = null, winCard = null;
        if (!timedOut && winner != null && stats.nSeats == 2 && bySeat[1 - winner] != null) {
            PlayerOutcome lost = bySeat[1 - winner].getOutcome();
            if (lost != null && lost.lossState != null) {
                winReason = lost.lossState.name();
                if ("SpellEffect".equals(winReason) || "OpponentWon".equals(winReason)) {
                    // OpponentWon: GameAction.checkGameOverCondition passes the winner's
                    // altWinSourceName into the loser's loseConditionSpell.
                    winCard = lost.loseConditionSpell;
                } else if ("CommanderDamage".equals(winReason)) {
                    winCard = stats.commanderAt21(bySeat[1 - winner]);
                }
            }
        }
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("game", g);
        rec.put("seed", seed);
        rec.put("winner_seat", winner);
        rec.put("end_reason", reason);
        rec.put("turns", lastTurn);
        rec.put("first_seat", stats.firstSeat);
        rec.put("ms", ms);
        rec.put("error", error);
        rec.put("win_reason", winReason);
        rec.put("win_card", winCard);
        List<Object> seats = new ArrayList<>();
        for (int seat = 0; seat < stats.nSeats; seat++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("seat", seat);
            s.put("deck_hash", specs.get(seat).deckHash());
            s.put("profile", specs.get(seat).profile());
            s.put("ai", specs.get(seat).ai());
            s.put("final_life", bySeat[seat] == null ? null : bySeat[seat].getLife());
            s.put("mulligans", stats.mulligans[seat]);
            s.put("lands_played", stats.landsPlayed[seat]);
            s.put("spells_cast", stats.spellsCast[seat]);
            s.put("commander_casts", stats.commanderCasts[seat]);
            s.put("eliminated_turn", stats.eliminatedTurn[seat]);
            seats.add(s);
        }
        rec.put("seats", seats);
        List<Object> cardRecs = new ArrayList<>();
        for (int seat = 0; seat < stats.nSeats; seat++) {
            for (Map.Entry<String, CardStat> e : stats.cards.get(seat).entrySet()) {
                CardStat cs = e.getValue();
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("seat", seat);
                c.put("name", e.getKey());
                c.put("in_opening_hand", cs.inOpeningHand);
                c.put("first_drawn_turn", cs.firstDrawnTurn);
                c.put("times_cast", cs.timesCast);
                c.put("first_cast_turn", cs.firstCastTurn);
                c.put("owner_casts", cs.ownerCasts);
                c.put("owner_first_cast_turn", cs.firstOwnerCastTurn);
                c.put("died", cs.died);
                c.put("final_zone", cs.finalZone);
                cardRecs.add(c);
            }
        }
        rec.put("cards", cardRecs);
        return rec;
    }

    /**
     * Last-resort record for when {@link #record} itself throws — possible on the
     * timeout path, where the still-running game thread may be writing the very maps
     * record() walks. Reads no live engine state and no collection this subscriber
     * maintains, so run() always has a record to print and a timeout still exits 3.
     */
    public static Map<String, Object> fallbackRecord(Stats stats, int g, long seed, List<JobFile.Seat> specs,
                                                     boolean timedOut, String error, Throwable t, long ms) {
        String msg = "record failed: " + t.getClass().getName() + ": " + t.getMessage();
        if (error != null) msg = error + " | " + msg;
        if (msg.length() > 400) msg = msg.substring(0, 400);
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("game", g);
        rec.put("seed", seed);
        rec.put("winner_seat", null);
        rec.put("end_reason", timedOut ? "Timeout" : "Error");
        rec.put("turns", stats.turn);
        rec.put("first_seat", stats.firstSeat);
        rec.put("ms", ms);
        rec.put("error", msg);
        rec.put("win_reason", null);
        rec.put("win_card", null);
        List<Object> seats = new ArrayList<>();
        for (int seat = 0; seat < stats.nSeats; seat++) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("seat", seat);
            s.put("deck_hash", specs.get(seat).deckHash());
            s.put("profile", specs.get(seat).profile());
            s.put("ai", specs.get(seat).ai());
            s.put("final_life", null);
            s.put("mulligans", 0);
            s.put("lands_played", 0);
            s.put("spells_cast", 0);
            s.put("commander_casts", 0);
            s.put("eliminated_turn", null);
            seats.add(s);
        }
        rec.put("seats", seats);
        rec.put("cards", new ArrayList<>());
        return rec;
    }

    /** The job's summary as Harness.run builds it: {@code {"summary": {"games": games, <counts>, "ms": totalMs}}}, the
     *  counts in {@code counts}' own order (END_REASONS', as JobRunner fills it). */
    public static Map<String, Object> summary(int games, Map<String, Integer> counts, long totalMs) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("games", games);
        summary.putAll(counts);
        summary.put("ms", totalMs);
        return Map.of("summary", summary);
    }

    // ------------------------------------------------------------------- json

    /** Minimal JSON writer for Map/List/String/Number/Boolean/null (Forge ships no JSON library). */
    public static final class Json {
        public static String write(Object o) {
            StringBuilder sb = new StringBuilder();
            write(sb, o);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        static void write(StringBuilder sb, Object o) {
            if (o == null) { sb.append("null"); return; }
            if (o instanceof String) { str(sb, (String) o); return; }
            if (o instanceof Number || o instanceof Boolean) { sb.append(o); return; }
            if (o instanceof Map) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<Object, Object> e : ((Map<Object, Object>) o).entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    str(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
                return;
            }
            if (o instanceof Iterable) {
                sb.append('[');
                boolean first = true;
                for (Object v : (Iterable<Object>) o) {
                    if (!first) sb.append(',');
                    first = false;
                    write(sb, v);
                }
                sb.append(']');
                return;
            }
            str(sb, String.valueOf(o));
        }

        static void str(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':  sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append('"');
        }
    }
}
