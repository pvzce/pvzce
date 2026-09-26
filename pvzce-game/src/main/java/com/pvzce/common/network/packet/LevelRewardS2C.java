package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;
import java.util.Optional;

/**
 * What a finished level paid out, for the award screen.
 *
 * <p>Sent after {@link GameStateS2C} (the bridge that reports the state calls the
 * reward code right after forwarding it), so the client may already be showing the
 * "victory" overlay when this arrives. It is presentation only: the server has
 * written the wallet before sending it, and a client that drops the packet still
 * got the coins.
 *
 * <p>{@code dropX}/{@code dropY} are the cell the last zombie died on, which is where the
 * reward falls; a non-finite value means "no zombie died" (a level with no waves) and the
 * client falls back to the middle of the board.
 *
 * <p>{@code collectedCoins} and {@code bonusCoins} are split because the original
 * award screen treats them differently - the first is what fell out of zombies
 * during the run, the second is the level's completion payout.
 *
 * <h2>{@code grants}, and why it is a list</h2>
 *
 * <p>Everything the run <em>handed over</em> other than coins travels as one ordered list of
 * {@link Grant}: a card the level unlocked, a level buff it switched on, a resource it paid in
 * objects rather than in cash. A clear may pay several of them at once, and the three used to be
 * three separate single-valued fields - so a level that unlocked two cards reported one and paid
 * the other in silence, and the page could only ever describe one thing.
 *
 * <p>The order is the priority the page draws in: {@code CARD} first, then {@code BUFF}, then
 * {@code ITEM}. The frame at the top of the award page holds one thing, so {@link #headline()}
 * is "the biggest news of the run", and what falls on the lawn is the same object. The rest are
 * listed underneath with their own descriptions.
 */
public record LevelRewardS2C(String levelId, int collectedCoins, int bonusCoins, int totalCoins,
                             List<Grant> grants, float dropX, float dropY,
                             int mowers, int mowerCoins) implements PvzcePacket {
    /**
     * One thing the run handed over.
     *
     * @param kind   which registry names the id, and where it sits in the page's priority
     * @param id     the content id: a card, a level buff, or a resource
     * @param amount how many, for an item; 1 for a card or a buff
     */
    public record Grant(Kind kind, String id, int amount) {
        /** What a grant names. The declaration order is the page's drawing order. */
        public enum Kind {
            /** A card the profile now owns: a plant or a tool. */
            CARD,
            /** A level buff the profile may now switch on. */
            BUFF,
            /** A resource paid in objects rather than in coins. */
            ITEM
        }

        public static final PacketStruct.Codec<Grant> CODEC = PacketStruct.<Grant>builder()
                .field(grant -> grant.kind().ordinal(), PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .field(Grant::id, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(Grant::amount, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .build(values -> new Grant(Kind.values()[(Integer) values.get(0)],
                        (String) values.get(1), (Integer) values.get(2)));

        public static Grant card(String id) {
            return new Grant(Kind.CARD, id, 1);
        }

        public static Grant buff(String id) {
            return new Grant(Kind.BUFF, id, 1);
        }

        public static Grant item(String id, int amount) {
            return new Grant(Kind.ITEM, id, amount);
        }

        public boolean hasId() {
            return id != null && !id.isEmpty();
        }

        public void encode(PacketByteBuf buf) {
            CODEC.encode(this, buf);
        }

        public static Grant decode(PacketByteBuf buf) {
            return CODEC.decode(buf);
        }
    }

    public static final PacketStruct.Codec<LevelRewardS2C> CODEC = PacketStruct.<LevelRewardS2C>builder()
            .field(LevelRewardS2C::levelId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(LevelRewardS2C::collectedCoins, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelRewardS2C::bonusCoins, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelRewardS2C::totalCoins, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            // Everything handed over that is not coins, in the page's own priority order. One
            // list rather than three fields: a clear can pay more than one thing, and the three
            // single-valued fields this replaces could only ever report the first of each.
            .list(LevelRewardS2C::grants, Grant::encode, Grant::decode)
            // Where the last zombie died, in cells: that is where the reward lands, the
            // way the original drops it on the spot the fight ended.
            .field(LevelRewardS2C::dropX, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
            .field(LevelRewardS2C::dropY, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
            // Lawn mowers that survived the level, and what they were worth. The count is
            // sent as well as the coins so the client's "each mower turns into a coin"
            // gesture shows exactly the number the wallet was paid for, rather than a
            // second count derived from its own mirror.
            .field(LevelRewardS2C::mowers, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelRewardS2C::mowerCoins, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .build(values -> new LevelRewardS2C((String) values.get(0), (Integer) values.get(1),
                    (Integer) values.get(2), (Integer) values.get(3), (List<Grant>) values.get(4),
                    (Float) values.get(5), (Float) values.get(6), (Integer) values.get(7),
                    (Integer) values.get(8)));

    public LevelRewardS2C {
        grants = grants == null ? List.of() : List.copyOf(grants);
    }

    /** The same payout landing on an explicit spot, with no mowers left over. */
    public LevelRewardS2C(String levelId, int collectedCoins, int bonusCoins, int totalCoins,
                          String unlockedCard, float dropX, float dropY) {
        this(levelId, collectedCoins, bonusCoins, totalCoins,
                unlockedCard == null || unlockedCard.isEmpty()
                        ? List.of() : List.of(Grant.card(unlockedCard)),
                dropX, dropY, 0, 0);
    }

    /** A payout with one item and mowers but no buff. */
    public LevelRewardS2C(String levelId, int collectedCoins, int bonusCoins, int totalCoins,
                          String unlockedCard, String rewardItem, int rewardItemAmount,
                          float dropX, float dropY, int mowers, int mowerCoins) {
        this(levelId, collectedCoins, bonusCoins, totalCoins,
                grantsOf(unlockedCard, "", rewardItem, rewardItemAmount), dropX, dropY,
                mowers, mowerCoins);
    }

    /** The same payout landing on an explicit spot; used by tests and by the smoke keys. */
    public LevelRewardS2C(String levelId, int collectedCoins, int bonusCoins, int totalCoins,
                          String unlockedCard) {
        this(levelId, collectedCoins, bonusCoins, totalCoins, unlockedCard, Float.NaN, Float.NaN);
    }

    /** A payout with no grants at all. */
    public static LevelRewardS2C coinsOnly(String levelId, int collectedCoins, int bonusCoins,
                                           int totalCoins) {
        return new LevelRewardS2C(levelId, collectedCoins, bonusCoins, totalCoins, List.of(),
                Float.NaN, Float.NaN, 0, 0);
    }

    /**
     * The three-way constructor's list, in the page's priority order.
     *
     * <p>Kept so the callers that predate the list (the smoke keys, the tests) keep their shape:
     * they name one card, one buff and one item, and the order they are put in here is the order
     * the page draws them.
     */
    public static List<Grant> grantsOf(String card, String buff, String item, int itemAmount) {
        List<Grant> grants = new java.util.ArrayList<>(3);
        if (card != null && !card.isEmpty()) {
            grants.add(Grant.card(card));
        }
        if (buff != null && !buff.isEmpty()) {
            grants.add(Grant.buff(buff));
        }
        if (item != null && !item.isEmpty() && itemAmount > 0) {
            grants.add(Grant.item(item, itemAmount));
        }
        return List.copyOf(grants);
    }

    /** Coins this level paid out in total. */
    public int awardedCoins() {
        return collectedCoins + bonusCoins;
    }

    /** True when this run handed anything over at all. */
    public boolean hasGrants() {
        return !grants.isEmpty();
    }

    /** The first grant of a kind, if the run paid one. */
    public Optional<Grant> grant(Grant.Kind kind) {
        return grants.stream().filter(grant -> grant.kind() == kind).findFirst();
    }

    /**
     * The biggest news of the run, or empty when it paid nothing but coins.
     *
     * <p>What the award page's frame draws and what falls on the lawn. The list is already in
     * priority order, so this is its head - one answer, not a second ranking.
     */
    public Optional<Grant> headline() {
        return grants.isEmpty() ? Optional.empty() : Optional.of(grants.get(0));
    }

    /** True when the run unlocked a card, i.e. the award screen shows a seed packet. */
    public boolean hasUnlock() {
        return headline().filter(grant -> grant.kind() == Grant.Kind.CARD).isPresent();
    }

    /** The card this run unlocked, or an empty string. */
    public String unlockedCard() {
        return headline().filter(grant -> grant.kind() == Grant.Kind.CARD)
                .map(Grant::id).orElse("");
    }

    /** True when this run unlocked a level buff. */
    public boolean hasUnlockedBuff() {
        return headline().filter(grant -> grant.kind() == Grant.Kind.BUFF).isPresent();
    }

    /** The buff this run unlocked, or an empty string. */
    public String unlockedBuff() {
        return headline().filter(grant -> grant.kind() == Grant.Kind.BUFF)
                .map(Grant::id).orElse("");
    }

    /**
     * True when the run was handed an object instead of a card (a diamond, say).
     *
     * <p>Asked only after {@link #hasUnlock()}: the frame holds one thing, and a card is
     * the bigger news of the two.
     */
    public boolean hasRewardItem() {
        return headline().filter(grant -> grant.kind() == Grant.Kind.ITEM).isPresent();
    }

    /** The resource this run was handed, or an empty string. */
    public String rewardItem() {
        return headline().filter(grant -> grant.kind() == Grant.Kind.ITEM)
                .map(Grant::id).orElse("");
    }

    /** How many of that resource, or zero. */
    public int rewardItemAmount() {
        return headline().filter(grant -> grant.kind() == Grant.Kind.ITEM)
                .map(Grant::amount).orElse(0);
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static LevelRewardS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
