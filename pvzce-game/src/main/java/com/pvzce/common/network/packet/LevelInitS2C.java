package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;
import com.pvzce.common.network.PvzcePackets;

import java.util.List;

/**
 * Full level state: the board payload, the card bar, the wave meter and which team
 * the receiving player currently controls.
 *
 * <p>The team travels here (and not only in {@link TeamSyncS2C}) so that any resync
 * restores the real team instead of resetting the client to the plant team.
 */
public record LevelInitS2C(String levelId, List<SlotInfo> slots, List<String> waveTypes,
                           LevelPayload payload, String controlledTeamId, String controlledTeamName,
                           int protocolVersion) implements PvzcePacket {
    public static final PacketStruct.Codec<LevelInitS2C> CODEC = PacketStruct.<LevelInitS2C>builder()
            .field(LevelInitS2C::protocolVersion, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(LevelInitS2C::levelId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .list(LevelInitS2C::slots, SlotInfo::encode, SlotInfo::decode)
            .stringList(LevelInitS2C::waveTypes)
            .nested(LevelInitS2C::payload, LevelPayload.CODEC)
            .field(LevelInitS2C::controlledTeamId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(LevelInitS2C::controlledTeamName, PacketByteBuf::writeString, PacketByteBuf::readString)
            .build(values -> new LevelInitS2C((String) values.get(1), (List<SlotInfo>) values.get(2),
                    (List<String>) values.get(3), (LevelPayload) values.get(4),
                    (String) values.get(5), (String) values.get(6), (Integer) values.get(0)));

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    public int width() {
        return payload.width();
    }

    public int height() {
        return payload.height();
    }

    public List<SeedOption> seedPool() {
        return payload.seedPool();
    }

    public int maxSeedSlots() {
        return payload.maxSeedSlots();
    }

    public List<String> previewZombies() {
        return payload.previewZombies();
    }

    public List<SceneSyncS2C.Cell> sceneCells() {
        return payload.sceneCells();
    }

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static LevelInitS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }

    /** The protocol version this packet was built with. */
    public static int currentProtocolVersion() {
        return PvzcePackets.PROTOCOL_VERSION;
    }
}
