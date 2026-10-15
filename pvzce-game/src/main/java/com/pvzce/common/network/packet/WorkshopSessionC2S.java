package com.pvzce.common.network.packet;
import com.pvzce.common.network.*;
/** Opens/closes a temporary workshop session; an empty workspace closes it. */
public record WorkshopSessionC2S(String workspace) implements PvzcePacket {
    public ConnectionDirection direction() { return ConnectionDirection.SERVERBOUND; }
    public void encode(PacketByteBuf buf) { buf.writeString(workspace); }
    public static WorkshopSessionC2S decode(PacketByteBuf buf) { return new WorkshopSessionC2S(buf.readString()); }
}
