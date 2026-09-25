package com.pvzce.common.shop;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;

import java.util.List;
import java.util.Optional;

/**
 * What the shop sells, and what it costs.
 *
 * <p>A table in code rather than a registry, for the same reason the mutation catalogue is: an
 * item is not content, it is a rule about how the player's record changes, and "the price of a
 * card slot" is a balance decision rather than a data file. Three built-ins and no mod hook yet -
 * a mod that wants to sell something needs a new {@code PlayerProfile} field first, which is the
 * part that is genuinely not extensible from data.
 *
 * <h2>The price lives here and nowhere else</h2>
 *
 * <p>The client draws the number it was told and sends back only <em>which</em> item it wants; the
 * server re-reads the price from this table. That is the same shape as buying a level
 * ({@code PvzceServer.buyLevel} re-derives the cost from the level definition), and it is what
 * makes a modified client unable to buy anything cheaply.
 *
 * <h2>Why the items are what they are</h2>
 *
 * <p>The original's shop sells the rake and the extra seed slot, and this one adds a third thing
 * that does not exist in the original at all (the sun shovel) because the project has a shovel and
 * a sun economy of its own. The order in the table is the order the page lists them: cheapest
 * first, which is also the order a new player can afford them.
 *
 * <p><b>The table only.</b> "How many of these has this world bought" is a question about a
 * profile, so it lives next to the profile in {@code server.shop.ShopPurchases}: this class is in
 * {@code common}, and {@code common} does not reach into {@code server} (see
 * {@code LayerDependencyTest}). The split is also the honest one - the catalogue is a fact about
 * the game, and ownership is a fact about a save.
 */
public final class ShopItems {
    /** One more card slot, up to {@link com.pvzce.common.PvzceConstants#MAX_SEED_SLOTS}. */
    public static final Identifier CARD_SLOT = PvzceIds.SHOP_CARD_SLOT;
    /** The sun shovel: digging a plant up returns a fifth of what it cost. */
    public static final Identifier SUN_SHOVEL = PvzceIds.BUFF_SUN_SHOVEL;
    /** The rake: one zombie per level, flattened before it reaches the house. */
    public static final Identifier RAKE = PvzceIds.RAKE;

    private ShopItems() {
    }

    /**
     * One line of the shop.
     *
     * @param id        what the packet names
     * @param price     in coins, charged once per purchase
     * @param maxOwned  how many times it may be bought in total; 1 for a one-off
     * @param kind      what the page draws next to it, and what the "already owned" test is
     */
    public record Item(Identifier id, int price, int maxOwned, Kind kind) {
        /** How the page presents the item, and how ownership is decided. */
        public enum Kind {
            /** Adds card slots; owned {@code purchased} times. */
            CARD_SLOTS,
            /** Grants a level buff the player did not have. */
            BUFF,
            /** Grants the rake, which every level then places by itself. */
            TOOL
        }
    }

    /** The catalogue, in the order the page lists it. */
    public static final List<Item> ITEMS = List.of(
            // 200: an early purchase a player can afford after two or three levels, and cheap
            // enough that "I will save it for later" is not the obvious answer.
            new Item(RAKE, 200, 1, Item.Kind.TOOL),
            // 1000: four of these take the backpack from 8 to 12, which is the ceiling. Priced so
            // that the whole set is a long-term goal rather than a shopping trip.
            new Item(CARD_SLOT, 1000, com.pvzce.common.PvzceConstants.MAX_SEED_SLOTS
                    - com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS, Item.Kind.CARD_SLOTS),
            // 1500: the most expensive thing in the shop, because it changes every level from then
            // on rather than one run.
            new Item(SUN_SHOVEL, 1500, 1, Item.Kind.BUFF));

    /**
     * How many times a world with these collections has bought the item.
     *
     * <p>Written over the three collections rather than over a profile so the client can ask the
     * same question: the page draws "已拥有 2/4" and the server decides whether a purchase is
     * allowed, and two implementations of that count would eventually disagree about a world whose
     * backpack an operator widened by hand.
     *
     * <p>Derived rather than counted - see {@code server.shop.ShopPurchases} for why.
     */
    public static int ownedCount(int seedSlots, java.util.Set<Identifier> unlocked,
                                 java.util.Set<Identifier> unlockedBuffs, Item item) {
        return switch (item.kind()) {
            case CARD_SLOTS -> Math.max(0,
                    seedSlots - com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS);
            case BUFF -> unlockedBuffs != null && unlockedBuffs.contains(item.id()) ? 1 : 0;
            case TOOL -> unlocked != null && unlocked.contains(item.id()) ? 1 : 0;
        };
    }

    /** True when a world with these collections cannot buy any more of it. */
    public static boolean maxedOut(int seedSlots, java.util.Set<Identifier> unlocked,
                                   java.util.Set<Identifier> unlockedBuffs, Item item) {
        return ownedCount(seedSlots, unlocked, unlockedBuffs, item) >= item.maxOwned();
    }

    /** The item with this id, if the catalogue has one. */
    public static Optional<Item> byId(Identifier id) {
        for (Item item : ITEMS) {
            if (item.id().equals(id)) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

}
