package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;
import java.util.List;

/** A world-scoped action checked by the running level before changing any state. */
public record StageChoiceC2S(String levelId, String worldName, int phase, List<String> selectedSeeds, List<String> selectedBuffs) implements PvzcePacket {
    public StageChoiceC2S {
        selectedSeeds = List.copyOf(selectedSeeds);
        selectedBuffs = List.copyOf(selectedBuffs);
    }
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    public static final PacketStruct.Codec<StageChoiceC2S> CODEC = PacketStruct.<StageChoiceC2S>builder()
            .field(StageChoiceC2S::levelId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(StageChoiceC2S::worldName, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(StageChoiceC2S::phase, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .stringList(StageChoiceC2S::selectedSeeds)
            .stringList(StageChoiceC2S::selectedBuffs)
            .build(v -> new StageChoiceC2S((String) v.get(0), (String) v.get(1), (Integer) v.get(2), (List<String>) v.get(3), (List<String>) v.get(4)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static StageChoiceC2S decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
