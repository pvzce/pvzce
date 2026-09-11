package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;

/** Level registry snapshot for the level select screen. */
public record LevelListS2C(List<LevelInfo> levels) implements PvzcePacket {
    public record TeamInfo(String id, String name, String winCondition) {
        public static final PacketStruct.Codec<TeamInfo> CODEC = PacketStruct.<TeamInfo>builder()
                .field(TeamInfo::id, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(TeamInfo::name, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(TeamInfo::winCondition, PacketByteBuf::writeString, PacketByteBuf::readString)
                .build(values -> new TeamInfo((String) values.get(0), (String) values.get(1),
                        (String) values.get(2)));

        public void encode(PacketByteBuf buf) {
            CODEC.encode(this, buf);
        }

        public static TeamInfo decode(PacketByteBuf buf) {
            return CODEC.decode(buf);
        }
    }

    /**
     * One selectable level.
     *
     * <p>The board description is a shared {@link LevelPayload}, so this packet and
     * {@link LevelInitS2C} cannot drift apart; the flat accessors below keep the
     * screens readable.
     */
    public record LevelInfo(String id, String name, String description, String winTeam,
                            List<TeamInfo> teams, String status, String icon, LevelPayload payload) {
        public static final PacketStruct.Codec<LevelInfo> CODEC = PacketStruct.<LevelInfo>builder()
                .field(LevelInfo::id, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(LevelInfo::name, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(LevelInfo::description, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(LevelInfo::winTeam, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(LevelInfo::status, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(LevelInfo::icon, PacketByteBuf::writeString, PacketByteBuf::readString)
                .list(LevelInfo::teams, TeamInfo::encode, TeamInfo::decode)
                .nested(LevelInfo::payload, LevelPayload.CODEC)
                .build(values -> new LevelInfo((String) values.get(0), (String) values.get(1),
                        (String) values.get(2), (String) values.get(3),
                        (List<TeamInfo>) values.get(6), (String) values.get(4), (String) values.get(5),
                        (LevelPayload) values.get(7)));

        public static LevelInfo of(String id, String name, String description, String winTeam,
                                   List<TeamInfo> teams, String status, String icon,
                                   LevelPayload payload) {
            return new LevelInfo(id, name, description, winTeam, teams, status, icon, payload);
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

        public void encode(PacketByteBuf buf) {
            CODEC.encode(this, buf);
        }

        public static LevelInfo decode(PacketByteBuf buf) {
            return CODEC.decode(buf);
        }
    }

    public static final PacketStruct.Codec<LevelListS2C> CODEC = PacketStruct.<LevelListS2C>builder()
            .list(LevelListS2C::levels, LevelInfo::encode, LevelInfo::decode)
            .build(values -> new LevelListS2C((List<LevelInfo>) values.get(0)));

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static LevelListS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
