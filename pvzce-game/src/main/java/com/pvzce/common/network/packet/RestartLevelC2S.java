package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;

/**
 * "Throw the saved run away and start this level over."
 *
 * <p>An explicit choice, so the server deletes the save instead of asking about it. Carries
 * a card bar because the two ways to reach it - the pause menu's 重新开始, which picks the
 * level's own cards again, and the save prompt's 重新开始, which asks for new ones - both
 * know which cards the fresh run should use.
 *
 * <p>The save prompt's "restart" opens the seed chooser first, so it arrives as
 * {@link PlayLevelC2S} with {@code restart()} set on that packet instead; this one is for the
 * restart that needs no chooser.
 */
public record RestartLevelC2S(String levelId, String worldName, List<String> selectedSeeds,
                              String humanTeam) implements PvzcePacket {
    public RestartLevelC2S {
        selectedSeeds = List.copyOf(selectedSeeds);
        humanTeam = humanTeam == null ? "" : humanTeam;
    }

    /** A restart from a caller with no side to state: the level's own default side is used. */
    public RestartLevelC2S(String levelId, String worldName, List<String> selectedSeeds) {
        this(levelId, worldName, selectedSeeds, "");
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    public static final PacketStruct.Codec<RestartLevelC2S> CODEC = PacketStruct.<RestartLevelC2S>builder()
    .field(RestartLevelC2S::levelId, PacketByteBuf::writeString, PacketByteBuf::readString)
    .field(RestartLevelC2S::worldName, PacketByteBuf::writeString, PacketByteBuf::readString)
    .stringList(RestartLevelC2S::selectedSeeds)
    .field(RestartLevelC2S::humanTeam, PacketByteBuf::writeString, PacketByteBuf::readString)
            .build(values -> new RestartLevelC2S((String) values.get(0), (String) values.get(1), (List<String>) values.get(2), (String) values.get(3)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static RestartLevelC2S decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
