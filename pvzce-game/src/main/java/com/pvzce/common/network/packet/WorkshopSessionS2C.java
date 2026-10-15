package com.pvzce.common.network.packet;
import com.pvzce.common.network.*;
/** Opens/closes a temporary workshop session; an empty workspace closes it. */
public record WorkshopSessionS2C(boolean active, String error) implements PvzcePacket {
    public ConnectionDirection direction() { return ConnectionDirection.CLIENTBOUND; }
    public void encode(PacketByteBuf buf) { buf.writeBoolean(active); buf.writeString(error); }
    public static WorkshopSessionS2C decode(PacketByteBuf buf) { return new WorkshopSessionS2C(buf.readBoolean(), buf.readString()); }
}
