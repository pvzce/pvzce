package com.pvzce.common.network.packet;

import com.pvzce.api.content.SurfaceProfile;
import com.pvzce.common.level.SceneBoard;
import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;
import java.util.List;

/** Scene element snapshot/mutation (S2C increment: SetElement{x,y,new}). */
public record SceneSyncS2C(List<Cell> cells) implements PvzcePacket {
    public record Cell(int x, int y, String elementId, String surfaceId, String baseId,
                       SurfaceProfile profile, float thickness, String surfaceName) {
        public Cell(int x, int y, String elementId, String surfaceId, String baseId,
                    SurfaceProfile profile, float thickness) {
            this(x, y, elementId, surfaceId, baseId, profile, thickness, "");
        }
        public Cell(int x, int y, String elementId) {
            this(x, y, elementId, SceneBoard.DEFAULT_SURFACE, elementId,
                    SurfaceProfile.FLAT, 0F);
        }
        public void encode(PacketByteBuf buf) {
            buf.writeInt(x); buf.writeInt(y); buf.writeString(elementId);
            buf.writeString(surfaceId); buf.writeString(baseId);
            writeProfile(buf, profile); buf.writeFloat(thickness); buf.writeString(surfaceName);
        }
        public static Cell decode(PacketByteBuf buf) {
            return new Cell(buf.readInt(), buf.readInt(), buf.readString(), buf.readString(),
                    buf.readString(), readProfile(buf), buf.readFloat(), buf.readString());
        }
    }
    public static void writeProfile(PacketByteBuf buf, SurfaceProfile p) {
        buf.writeFloat(p.elevation()); buf.writeFloat(p.slopeX()); buf.writeFloat(p.slopeY());
        buf.writeFloat(p.min()); buf.writeFloat(p.max());
    }
    public static SurfaceProfile readProfile(PacketByteBuf buf) {
        return new SurfaceProfile(buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat());
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
