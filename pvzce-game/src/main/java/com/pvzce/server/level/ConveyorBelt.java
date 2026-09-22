package com.pvzce.server.level;

import com.pvzce.api.content.LevelBelt;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.Tag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The running state of a level's conveyor belt: which cards are on it, in order, and
 * when the next one arrives.
 *
 * <p>The belt is a queue, not a deck. Cards ride in from the right and wait; the player
 * spends them by planting, and the belt only produces while it has room, which is what
 * makes holding a card a decision (the original stops delivering once the belt is full,
 * so an unused Wall-nut costs you a future one). Everything about the shape of the belt
 * - rate, capacity, pool - comes from {@link LevelBelt}; this class only tracks the
 * queue and the clock.
 *
 * <p>Card ids are handed out from a counter that never goes backwards and are what the
 * client sends back when it places a card. They are deliberately <em>not</em> the card's
 * position: a position shifts every time the front card is spent, so a click that raced
 * the belt would place the wrong plant. With an id, a stale click names a card that is no
 * longer there and is simply refused.
 */
public final class ConveyorBelt {
    /** One card currently on the belt. */
    public record Card(int id, Identifier cardId) {
    }

    private final LevelBelt def;
    private final List<Card> cards = new ArrayList<>();
    /**
     * How many of each card this belt has handed out, ever.
     *
     * <p>The count a card's {@code max_count} is measured against, and it counts
     * <em>deliveries</em> rather than plants: the original's thirteen grave busters are
     * thirteen cards the belt offered, whether or not the player used them. Spent and
     * unspent cards both count, which is why this is not "what is on the belt".
     */
    private final Map<Identifier, Integer> dealt = new HashMap<>();
    private int nextId;
    private int ticksUntilNext;
    private boolean changed = true;

    public ConveyorBelt(LevelBelt def, Random random) {
        this.def = def;
        for (int i = 0; i < def.initialCards(); i++) {
            produce(random);
        }
        this.ticksUntilNext = def.intervalTicks();
        this.changed = true;
    }

    public LevelBelt def() {
        return def;
    }

    public List<Card> cards() {
        return List.copyOf(cards);
    }

    public boolean isFull() {
        return cards.size() >= def.capacity();
    }

    /** True when the contents changed since the last {@link #clearChanged()}. */
    public boolean isChanged() {
        return changed;
    }

    public void clearChanged() {
        changed = false;
    }

    /** True when the pool still holds a card this belt may deal. */
    public boolean canProduce() {
        return def.canDeal(card -> dealt.getOrDefault(card, 0));
    }

    /** Advances the delivery clock by one server tick. */
    public void tick(Random random) {
        if (isFull() || !canProduce()) {
            // A full belt is not "behind schedule": it resumes one interval after a card is
            // taken, rather than dumping the backlog it accumulated while full.
            ticksUntilNext = def.intervalTicks();
            return;
        }
        if (ticksUntilNext > 0) {
            ticksUntilNext--;
        }
        if (ticksUntilNext <= 0) {
            produce(random);
            ticksUntilNext = def.intervalTicks();
        }
    }

    private void produce(Random random) {
        Identifier cardId = def.pick(random, card -> dealt.getOrDefault(card, 0));
        if (cardId == null) {
            return;
        }
        dealt.merge(cardId, 1, Integer::sum);
        cards.add(new Card(nextId++, cardId));
        changed = true;
    }

    public Card card(int id) {
        for (Card card : cards) {
            if (card.id() == id) {
                return card;
            }
        }
        return null;
    }

    /** Spends one card; returns it, or {@code null} when it is no longer on the belt. */
    public Card take(int id) {
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).id() == id) {
                Card taken = cards.remove(i);
                changed = true;
                return taken;
            }
        }
        return null;
    }

    /** Drops every card; used when a level is rebuilt from scratch. */
    public void clear() {
        cards.clear();
        changed = true;
    }

    /** Belt contents belong to the run: leaving and resuming must not refill the queue. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("NextId", nextId);
        tag.putInt("TicksUntilNext", ticksUntilNext);
        ListTag list = new ListTag();
        for (Card card : cards) {
            CompoundTag cardTag = new CompoundTag();
            cardTag.putInt("id", card.id());
            cardTag.putString("card", card.cardId().toString());
            list.add(cardTag);
        }
        tag.put("Cards", list);
        // The delivery counts ride with the queue: a resumed run must not be handed a
        // fourteenth grave buster because the tally was forgotten.
        ListTag dealtList = new ListTag();
        for (Map.Entry<Identifier, Integer> entry : dealt.entrySet()) {
            CompoundTag countTag = new CompoundTag();
            countTag.putString("card", entry.getKey().toString());
            countTag.putInt("count", entry.getValue());
            dealtList.add(countTag);
        }
        tag.put("Dealt", dealtList);
        return tag;
    }

    /** Restores a saved belt; unknown card ids are dropped rather than kept as blanks. */
    public void restore(CompoundTag tag) {
        cards.clear();
        dealt.clear();
        nextId = Math.max(0, tag.getInt("NextId"));
        ticksUntilNext = Math.max(0, tag.getInt("TicksUntilNext"));
        for (Tag element : tag.getList("Dealt").values()) {
            if (!(element instanceof CompoundTag countTag)) {
                continue;
            }
            Identifier cardId = Identifier.tryParse(countTag.getString("card"));
            if (cardId != null) {
                dealt.merge(cardId, Math.max(0, countTag.getInt("count")), Integer::sum);
            }
        }
        for (Tag element : tag.getList("Cards").values()) {
            if (!(element instanceof CompoundTag cardTag)) {
                continue;
            }
            Identifier cardId = Identifier.tryParse(cardTag.getString("card"));
            if (cardId != null) {
                cards.add(new Card(cardTag.getInt("id"), cardId));
                nextId = Math.max(nextId, cardTag.getInt("id") + 1);
            }
        }
        changed = true;
    }

    /** String form of the belt, for logs. */
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        for (Card card : cards) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(card.id()).append(':').append(card.cardId());
        }
        return "[" + builder + "]";
    }
}
