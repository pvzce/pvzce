package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

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
 * during the run, the second is the level's completion payout. {@code unlockedCard}
 * is empty unless this run unlocked something, and it is what decides between a
 * dropping seed packet and a money bag.
 *
 * <p>{@code rewardItem} is the third shape of the same idea: a level that pays a
 * {@code resource} reward hands the player an object, not a sum, and the award page draws
 * that object in the frame. Its coin value is already inside {@code bonusCoins} - the wallet
 * holds one number - so this pair is presentation only, exactly like {@code unlockedCard}.
 * A run that unlocked a card and a run that paid an item are alternatives on screen: the
 * frame holds one thing, and the card wins.
 */
public record LevelRewardS2C(String levelId, int collectedCoins, int bonusCoins, int totalCoins,
                             String unlockedCard, String rewardItem, int rewardItemAmount,
                             float dropX, float dropY,
                             int mowers, int mowerCoins) implements PvzcePacket {
    public static final PacketStruct.Codec<LevelRewardS2C> CODEC = PacketStruct.<LevelRewardS2C>builder()
            .field(LevelRewardS2C::levelId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(LevelRewardS2C::collectedCoins, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelRewardS2C::bonusCoins, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelRewardS2C::totalCoins, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelRewardS2C::unlockedCard, PacketByteBuf::writeString, PacketByteBuf::readString)
            // What the run was handed, when the level pays in objects rather than in cards:
            // the resource id the award page draws, and how many of it. Empty means the
            // reward is the money bag, which is every level that declares no resource entry.
            .field(LevelRewardS2C::rewardItem, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(LevelRewardS2C::rewardItemAmount, PacketByteBuf::writeInt, PacketByteBuf::readInt)
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
                    (Integer) values.get(2), (Integer) values.get(3), (String) values.get(4),
                    (String) values.get(5), (Integer) values.get(6),
                    (Float) values.get(7), (Float) values.get(8), (Integer) values.get(9),
                    (Integer) values.get(10)));

    /** The same payout landing on an explicit spot, with no mowers left over. */
    public LevelRewardS2C(String levelId, int collectedCoins, int bonusCoins, int totalCoins,
                          String unlockedCard, float dropX, float dropY) {
        this(levelId, collectedCoins, bonusCoins, totalCoins, unlockedCard, "", 0, dropX, dropY, 0, 0);
    }

    /** The same payout landing on an explicit spot; used by tests and by the smoke keys. */
    public LevelRewardS2C(String levelId, int collectedCoins, int bonusCoins, int totalCoins,
                          String unlockedCard) {
        this(levelId, collectedCoins, bonusCoins, totalCoins, unlockedCard, Float.NaN, Float.NaN);
    }

    /** Coins this level paid out in total. */
    public int awardedCoins() {
        return collectedCoins + bonusCoins;
    }

    /** True when the run unlocked a card, i.e. the award screen shows a seed packet. */
    public boolean hasUnlock() {
        return unlockedCard != null && !unlockedCard.isEmpty();
    }

    /**
     * True when the run was handed an object instead of a card (a diamond, say).
     *
     * <p>Asked only after {@link #hasUnlock()}: the frame holds one thing, and a card is
     * the bigger news of the two.
     */
    public boolean hasRewardItem() {
        return rewardItem != null && !rewardItem.isEmpty() && rewardItemAmount > 0;
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
