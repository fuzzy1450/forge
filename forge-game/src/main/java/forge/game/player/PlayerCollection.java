package forge.game.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.zone.ZoneType;
import forge.util.Aggregates;
import forge.util.IterableUtil;
import forge.util.collect.FCollection;

public class PlayerCollection extends FCollection<Player> {

    private static final long serialVersionUID = -4374566955977201748L;

    public PlayerCollection() {
    }
    
    public PlayerCollection(Iterable<Player> players) {
        this.addAll(players); 
    }

    public PlayerCollection(Player player) {
        this.add(player);
    }

    // card collection functions
    public final CardCollection getCardsIn(ZoneType zone) {
        // the players' cards are gathered as they are and checked for duplicates once, at the end
        final List<Card> cards = new ArrayList<>();
        for (Player p : this) {
            CardCollection.appendTo(cards, p.getCardsIn(zone));
        }
        return CardCollection.copyOf(cards);
    }

    public final CardCollection getCardsIn(Iterable<ZoneType> zones) {
        final List<Card> cards = new ArrayList<>();
        for (Player p : this) {
            CardCollection.appendTo(cards, p.getCardsIn(zones));
        }
        return CardCollection.copyOf(cards);
    }
    
    public final CardCollection getCreaturesInPlay() {
        final List<Card> cards = new ArrayList<>();
        for (Player p : this) {
            CardCollection.appendTo(cards, p.getCreaturesInPlay());
        }
        return CardCollection.copyOf(cards);
    }
    
    // filter functions with predicate
    public PlayerCollection filter(Predicate<Player> pred) {
        return new PlayerCollection(IterableUtil.filter(this, pred));
    }
    
    // sort functions with Comparator
    public Player min(Comparator<Player> comp) {
        if (this.isEmpty()) return null;
        return Collections.min(this, comp);
    }
    public Player max(Comparator<Player> comp) {
        if (this.isEmpty()) return null;
        return Collections.max(this, comp);
    }
    
    // value functions with Function
    //TODO: Could probably move these up to FCollectionView, apply them, and trim off a bunch of "Aggregates" clauses.
    public Integer min(Function<Player, Integer> func) {
        return Aggregates.min(this, func);
    }
    public Integer max(Function<Player, Integer> func) {
        return Aggregates.max(this, func);
    }
    public Integer sum(Function<Player, Integer> func) {
        return Aggregates.sum(this, func);
    }
}
