package com.pvzce.server.level;

import com.pvzce.api.content.LevelBelt;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
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

    /** Advances the delivery clock by one server tick. */
    public void tick(Random random) {
        if (isFull() || !def.producesCards()) {
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
        Identifier cardId = def.pick(random);
        if (cardId == null) {
            return;
        }
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
        return tag;
    }

    /** Restores a saved belt; unknown card ids are dropped rather than kept as blanks. */
    public void restore(CompoundTag tag) {
        cards.clear();
        nextId = Math.max(0, tag.getInt("NextId"));
        ticksUntilNext = Math.max(0, tag.getInt("TicksUntilNext"));
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
