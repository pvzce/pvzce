package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;

/**
 * "These cards for the next round."
 *
 * <p>The endless card chooser's 开始下一轮, and the only packet that changes the bar of a level
 * that is already running. {@link PlayLevelC2S} cannot do it: that one starts a run, and this one
 * has to keep the run it is in - the lawn, the sun, the mowers, everything the player built over
 * the last round is exactly what they are defending with the new cards.
 *
 * <p>The buffs are deliberately not here. A round does not re-open the buff page: the run's buffs
 * were chosen before it began, and a chooser that could rewrite them every round would turn a
 * build decision into a per-round toggle.
 *
 * @param levelId       the level the player is in, so a stale choice cannot land on another one
 * @param worldName     the world it is running in
 * @param selectedSeeds the new bar; an empty list is "no cards", not "no answer"
 */
public record ReselectCardsC2S(String levelId, String worldName, List<String> selectedSeeds)
        implements PvzcePacket {
    public ReselectCardsC2S {
        selectedSeeds = List.copyOf(selectedSeeds);
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    public static final PacketStruct.Codec<ReselectCardsC2S> CODEC =
            PacketStruct.<ReselectCardsC2S>builder()
                    .field(ReselectCardsC2S::levelId, PacketByteBuf::writeString,
                            PacketByteBuf::readString)
                    .field(ReselectCardsC2S::worldName, PacketByteBuf::writeString,
                            PacketByteBuf::readString)
                    .stringList(ReselectCardsC2S::selectedSeeds)
                    .build(values -> new ReselectCardsC2S((String) values.get(0), (String) values.get(1),
                            (List<String>) values.get(2)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static ReselectCardsC2S decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
