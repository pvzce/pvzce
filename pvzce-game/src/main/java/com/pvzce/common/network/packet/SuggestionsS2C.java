package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
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

    public static final PacketStruct.Codec<SuggestionsS2C> CODEC = PacketStruct.<SuggestionsS2C>builder()
    .field(SuggestionsS2C::requestId, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .list(SuggestionsS2C::suggestions, Suggestion::encode, Suggestion::decode)
            .build(values -> new SuggestionsS2C((Integer) values.get(0), (List<Suggestion>) values.get(1)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static SuggestionsS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
