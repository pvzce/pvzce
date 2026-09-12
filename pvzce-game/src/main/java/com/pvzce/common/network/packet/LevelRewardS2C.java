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
 */
public record LevelRewardS2C(String levelId, int collectedCoins, int bonusCoins, int totalCoins,
                             String unlockedCard, float dropX, float dropY) implements PvzcePacket {
    public static final PacketStruct.Codec<LevelRewardS2C> CODEC = PacketStruct.<LevelRewardS2C>builder()
            .field(LevelRewardS2C::levelId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(LevelRewardS2C::collectedCoins, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelRewardS2C::bonusCoins, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelRewardS2C::totalCoins, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelRewardS2C::unlockedCard, PacketByteBuf::writeString, PacketByteBuf::readString)
            // Where the last zombie died, in cells: that is where the reward lands, the
            // way the original drops it on the spot the fight ended.
            .field(LevelRewardS2C::dropX, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
            .field(LevelRewardS2C::dropY, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
            .build(values -> new LevelRewardS2C((String) values.get(0), (Integer) values.get(1),
                    (Integer) values.get(2), (Integer) values.get(3), (String) values.get(4),
                    (Float) values.get(5), (Float) values.get(6)));

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
