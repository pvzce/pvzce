package com.pvzce.common.network.packet;

import com.pvzce.common.network.*;
import java.util.List;

/** Enabled host packs, in resource stack order. */
public record PackManifestS2C(List<Pack> packs, boolean requireAll) implements PvzcePacket {
    public record Pack(String name, String hash, int bytes, boolean required) {
        public static final PacketStruct.Codec<Pack> CODEC = PacketStruct.<Pack>builder()
                .field(Pack::name, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(Pack::hash, PacketByteBuf::writeString, PacketByteBuf::readString)
                .field(Pack::bytes, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                .field(Pack::required, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
                .build(v -> new Pack((String)v.get(0), (String)v.get(1), (Integer)v.get(2), (Boolean)v.get(3)));
        public void encode(PacketByteBuf buf) { CODEC.encode(this, buf); }
        public static Pack decode(PacketByteBuf buf) { return CODEC.decode(buf); }
    }
    @Override public ConnectionDirection direction() { return ConnectionDirection.CLIENTBOUND; }
    @Override public void encode(PacketByteBuf buf) { buf.writeList(packs, Pack::encode); buf.writeBoolean(requireAll); }
    public static PackManifestS2C decode(PacketByteBuf buf) { return new PackManifestS2C(buf.readList(Pack::decode), buf.readBoolean()); }
}
