package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;

/** Scene element snapshot/mutation (S2C increment: SetElement{x,y,new}). */
public record SceneSyncS2C(List<Cell> cells) implements PvzcePacket {
    public record Cell(int x, int y, String elementId) {
        public static final com.pvzce.common.network.PacketStruct.Codec<Cell> CODEC =
                com.pvzce.common.network.PacketStruct.<Cell>builder()
                        .field(Cell::x, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(Cell::y, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(Cell::elementId, PacketByteBuf::writeString, PacketByteBuf::readString)
                        .build(values -> new Cell((Integer) values.get(0), (Integer) values.get(1),
                                (String) values.get(2)));

        public void encode(PacketByteBuf buf) {
            CODEC.encode(this, buf);
        }

        public static Cell decode(PacketByteBuf buf) {
            return CODEC.decode(buf);
        }
    }

    public static SceneSyncS2C of(int x, int y, String elementId) {
        return new SceneSyncS2C(List.of(new Cell(x, y, elementId)));
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeList(cells, Cell::encode);
    }

    public static SceneSyncS2C decode(PacketByteBuf buf) {
        return new SceneSyncS2C(buf.readList(Cell::decode));
    }
}
