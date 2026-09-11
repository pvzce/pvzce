package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

import java.util.List;

/** MC-style tab-completion suggestions with replacement ranges. */
public record SuggestionsS2C(int requestId, List<Suggestion> suggestions) implements PvzcePacket {
    public record Suggestion(int start, int end, String text) {
        public void encode(PacketByteBuf buf) {
            buf.writeInt(start);
            buf.writeInt(end);
            buf.writeString(text);
        }

        public static Suggestion decode(PacketByteBuf buf) {
            return new Suggestion(buf.readInt(), buf.readInt(), buf.readString());
        }

        public String apply(String input) {
            int from = Math.max(0, Math.min(start, input.length()));
            int to = Math.max(from, Math.min(end, input.length()));
            return input.substring(0, from) + text + input.substring(to);
        }
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeInt(requestId);
        buf.writeList(suggestions, Suggestion::encode);
    }

    public static SuggestionsS2C decode(PacketByteBuf buf) {
        return new SuggestionsS2C(buf.readInt(), buf.readList(Suggestion::decode));
    }
}
