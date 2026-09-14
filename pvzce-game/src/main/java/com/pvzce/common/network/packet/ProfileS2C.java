package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;

/**
 * A world's persistent player record, as the menus need it: the coin wallet, the
 * unlocked cards, the levels bought outright, and how many card slots the backpack holds.
 *
 * <p>The server already filters every card pool by this profile, so this packet
 * exists only so the level select can draw the coin counter and the backpack can
 * grey out what is still locked. It is sent with the level list and again after
 * every change (a level ends, a command unlocks something), so the two never
 * disagree for longer than one frame.
 *
 * <p>{@code unlockAll} is carried separately from {@code unlocked} because a
 * sandbox world answers "do you own this" with yes for cards that are not in the
 * list - including cards added later - and the backpack has to render that
 * correctly instead of showing everything as locked.
 *
 * <p>{@code seedSlots} is the backpack's card-slot count. It travels because the
 * backpack page displays it and because a level that declares no
 * {@code max_seed_slots} is sized by it - the server resolves that into the
 * {@code LevelPayload}, so the chooser and the bar already agree without asking.
 */
public record ProfileS2C(int coins, List<String> unlocked, boolean unlockAll,
                         List<String> unlockedLevels, int seedSlots) implements PvzcePacket {
    public static final PacketStruct.Codec<ProfileS2C> CODEC = PacketStruct.<ProfileS2C>builder()
            .field(ProfileS2C::coins, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .stringList(ProfileS2C::unlocked)
            .field(ProfileS2C::unlockAll, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            // Kept apart from ``unlocked`` (cards): a level purchase must not be readable
            // as "this player owns a card called yard/adventure/1_2".
            .stringList(ProfileS2C::unlockedLevels)
            .field(ProfileS2C::seedSlots, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .build(values -> new ProfileS2C((Integer) values.get(0), (List<String>) values.get(1),
                    (Boolean) values.get(2), (List<String>) values.get(3), (Integer) values.get(4)));

    /** Before card slots travelled; kept for the tests that only care about cards. */
    public ProfileS2C(int coins, List<String> unlocked, boolean unlockAll) {
        this(coins, unlocked, unlockAll, List.of(),
                com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS);
    }

    /** Before card slots travelled but with level purchases. */
    public ProfileS2C(int coins, List<String> unlocked, boolean unlockAll, List<String> unlockedLevels) {
        this(coins, unlocked, unlockAll, unlockedLevels,
                com.pvzce.common.PvzceConstants.DEFAULT_SEED_SLOTS);
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static ProfileS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
