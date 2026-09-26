package com.pvzce.server.shop;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.shop.ShopItems;
import com.pvzce.server.PlayerProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * What this world already owns, and what buying one more of it would do.
 *
 * <p>The half of the shop that is about a save rather than about the game, which is why it lives
 * in {@code server} next to {@link PlayerProfile} while the catalogue itself lives in
 * {@code common.shop}: {@code common} does not reach into {@code server}, and a table of prices
 * that imported a profile would have to.
 *
 * <h2>Ownership is derived, never counted</h2>
 *
 * <p>There is no purchase counter. How many card slots a world has bought <em>is</em> how much
 * wider its backpack is than it starts; whether it owns the rake <em>is</em> whether the rake is in
 * its unlocked set; whether it owns the sun shovel <em>is</em> whether that buff is in its buff
 * set. A counter next to the thing it counts is a second copy of one fact, and this project
 * already has an operator door ({@code /profile slots}, {@code /profile buff}) that can change the
 * first copy without touching the second. The cost of deriving it is that a purchase has to move
 * the thing itself rather than a number - which is exactly what the server does.
 */
public final class ShopPurchases {
    private ShopPurchases() {
    }

    /** How many times this world has bought the item; the rule itself is in {@code ShopItems}. */
    public static int ownedCount(PlayerProfile profile, ShopItems.Item item) {
        if (profile == null) {
            return 0;
        }
        return ShopItems.ownedCount(profile.seedSlots(), profile.unlocked(),
                profile.unlockedBuffs(), item);
    }

    /** True when the world cannot buy any more of it. */
    public static boolean maxedOut(PlayerProfile profile, ShopItems.Item item) {
        return ownedCount(profile, item) >= item.maxOwned();
    }

    /**
     * Applies one purchase to the profile, or reports why it could not.
     *
     * <p>The one place a purchase changes anything, so the price check, the ceiling and the effect
     * cannot drift apart between the menu and a command. Charging is the caller's - this only
     * moves the thing that was bought - and the caller must have already checked
     * {@link #maxedOut} and the wallet.
     *
     * @return an empty string on success, or the reason it was refused
     */
    public static String apply(PlayerProfile profile, ShopItems.Item item) {
        if (profile == null) {
            return "没有玩家档案。";
        }
        if (maxedOut(profile, item)) {
            return "已经拥有 " + item.id() + " 了。";
        }
        return switch (item.kind()) {
            case CARD_SLOTS -> {
                int granted = profile.addSeedSlots(1);
                yield granted <= 0
                        ? "卡槽已经是上限 " + com.pvzce.common.PvzceConstants.MAX_SEED_SLOTS + " 了。"
                        : "";
            }
            case BUFF -> profile.unlockBuff(item.id()) ? "" : "这个增益已经有了。";
            case UNLOCK -> profile.unlock(item.id()) ? "" : "这个东西已经有了。";
        };
    }

    /**
     * One line per item, for the shop page.
     *
     * <p>Sent to the client so the page can draw prices and "已拥有" without a second copy of the
     * catalogue - the same rule every other menu follows: the server owns the numbers, the client
     * draws them. A modified client cannot buy anything cheaply because the purchase packet names
     * the item and never the price.
     */
    public static List<Entry> entriesFor(PlayerProfile profile) {
        List<Entry> entries = new ArrayList<>(ShopItems.ITEMS.size());
        for (ShopItems.Item item : ShopItems.ITEMS) {
            int owned = ownedCount(profile, item);
            entries.add(new Entry(item.id().toString(), item.price(), item.maxOwned(), owned,
                    item.kind().name().toLowerCase(java.util.Locale.ROOT),
                    item.kind() == ShopItems.Item.Kind.BUFF
                            && profile != null && profile.ownsBuff(item.id())));
        }
        return List.copyOf(entries);
    }

    /** One item as the page needs it. */
    public record Entry(String id, int price, int maxOwned, int owned, String kind,
                        boolean alreadyGranted) {
    }

    /** Every item id the shop knows; for the tests and for a command that lists them. */
    public static List<Identifier> itemIds() {
        List<Identifier> ids = new ArrayList<>(ShopItems.ITEMS.size());
        for (ShopItems.Item item : ShopItems.ITEMS) {
            ids.add(item.id());
        }
        return List.copyOf(ids);
    }

    /** True when this id names something the shop sells. */
    public static boolean sells(Identifier id) {
        return id != null && ShopItems.byId(id).isPresent();
    }

    /** The sun shovel's own id, spelled out for the one command that grants it. */
    public static Identifier sunShovel() {
        return PvzceIds.BUFF_SUN_SHOVEL;
    }
}
