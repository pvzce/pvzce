package com.pvzce.common.level.mutation;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.core.Slot;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.StringTag;
import com.pvzce.common.nbt.Tag;
import com.pvzce.server.PvzcePlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * A card bar as NBT, and back.
 *
 * <p>Two mutations hand the player a different bar and both have to be able to describe the one
 * they are replacing: the replacement mutation records the bar it overwrote so evicting it can put
 * the old one back, and a save taken while it is running has to carry that record or resuming
 * would leave the player with cards they never chose and no way back to them.
 *
 * <p>Card ids only, in bar order. Everything else about a slot - its kind, its price, its cooldown
 * - is derived from the card id by {@code SlotResolver}, which is the same call that built the bar
 * in the first place; storing those too would be a second copy of the content definition.
 */
final class MutationCards {
    /** The key a card list is written under, inside whichever block needs one. */
    static final String KEY_CARDS = "Cards";

    private MutationCards() {
    }

    /** One entry per card in the bar, in bar order. */
    static List<Identifier> idsOf(PvzcePlayer player) {
        if (player == null) {
            return List.of();
        }
        List<Identifier> ids = new ArrayList<>();
        for (Slot slot : player.slots()) {
            ids.add(slot.defId());
        }
        return List.copyOf(ids);
    }

    /** Writes a card list under {@value #KEY_CARDS}. */
    static void save(CompoundTag tag, List<Identifier> cards) {
        ListTag list = new ListTag();
        for (Identifier id : cards) {
            list.add(new StringTag(id.toString()));
        }
        tag.put(KEY_CARDS, list);
    }

    /** Reads what {@link #save} wrote; empty when the block is missing or unreadable. */
    static List<Identifier> load(CompoundTag tag) {
        List<Identifier> cards = new ArrayList<>();
        if (tag == null) {
            return List.of();
        }
        for (Tag element : tag.getList(KEY_CARDS).values()) {
            if (element instanceof StringTag stringTag) {
                Identifier id = Identifier.tryParse(stringTag.value());
                if (id != null) {
                    cards.add(id);
                }
            }
        }
        return List.copyOf(cards);
    }

    /** The bar those cards describe, ready to be handed to {@code PvzcePlayer.replaceSlots}. */
    static List<Slot> slotsOf(List<Identifier> cards) {
        return PvzcePlayer.deckSlots(cards);
    }
}
