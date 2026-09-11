package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/** Client asks the server for Brigadier tab-completion suggestions. */
public record RequestSuggestionsC2S(String input, int requestId) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(input);
        buf.writeInt(requestId);
    }

    public static RequestSuggestionsC2S decode(PacketByteBuf buf) {
        return new RequestSuggestionsC2S(buf.readString(), buf.readInt());
    }
}
