package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;

/**
 * "Start this level with this card bar."
 *
 * <p>The seed chooser's 开始游戏, and the only entry packet that carries a player's card
 * selection. Choosing cards only happens before a run begins, so this always means a fresh
 * run: the server does not resume a save into a bar the player just replaced.
 *
 * <p>{@code restart} distinguishes the two ways the chooser is reached. From the level list it
 * is a run that has not started yet, and a save on disk (the player closed the game
 * mid-level) is loaded and <em>then</em> asked about, exactly as {@link ContinueLevelC2S}
 * would be. From the save prompt's 重新开始 the player has already decided against that save,
 * so it is discarded.
 *
 * <p>An empty {@code selectedSeeds} means the player intentionally starts with no cards, which
 * is different from "no selection was sent" - that case is {@link ContinueLevelC2S}. The same
 * holds for {@code selectedBuffs}, with one case on top: because the chooser always opens its
 * buff page with the world's auto list already applied, an empty buff list really is "none this
 * time" - and it is also what becomes the world's new auto list.
 */
public record PlayLevelC2S(String levelId, String worldName, boolean restart,
                           List<String> selectedSeeds, List<String> selectedBuffs) implements PvzcePacket {
    public PlayLevelC2S {
        selectedSeeds = List.copyOf(selectedSeeds);
        selectedBuffs = List.copyOf(selectedBuffs);
    }

    /** A request from a caller that has no buff selection to send. */
    public PlayLevelC2S(String levelId, String worldName, boolean restart, List<String> selectedSeeds) {
        this(levelId, worldName, restart, selectedSeeds, List.of());
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    public static final PacketStruct.Codec<PlayLevelC2S> CODEC = PacketStruct.<PlayLevelC2S>builder()
    .field(PlayLevelC2S::levelId, PacketByteBuf::writeString, PacketByteBuf::readString)
    .field(PlayLevelC2S::worldName, PacketByteBuf::writeString, PacketByteBuf::readString)
    .field(PlayLevelC2S::restart, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
    .stringList(PlayLevelC2S::selectedSeeds)
    .stringList(PlayLevelC2S::selectedBuffs)
            .build(values -> new PlayLevelC2S((String) values.get(0), (String) values.get(1), (Boolean) values.get(2), (List<String>) values.get(3), (List<String>) values.get(4)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static PlayLevelC2S decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
