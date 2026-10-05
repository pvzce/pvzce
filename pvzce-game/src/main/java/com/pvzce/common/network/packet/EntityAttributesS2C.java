package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

import java.util.Map;

/** Complete computed attribute snapshot, sent at spawn/resync and when values change. */
public record EntityAttributesS2C(int entityId, Map<String, Double> values) implements PvzcePacket {
    public EntityAttributesS2C {
        values = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(values));
        if (values.values().stream().anyMatch(value -> !Double.isFinite(value)))
            throw new IllegalArgumentException("Synced attribute values must be finite");
    }

    public static final PacketStruct.Codec<EntityAttributesS2C> CODEC = PacketStruct.<EntityAttributesS2C>builder()
            .field(EntityAttributesS2C::entityId, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(EntityAttributesS2C::values,
                    (buf, values) -> buf.writeMap(values, (id, b) -> b.writeString(id), (value, b) -> b.writeDouble(value)),
                    buf -> buf.readMap(PacketByteBuf::readString, PacketByteBuf::readDouble))
            .build(values -> new EntityAttributesS2C((Integer) values.get(0), attributeValues(values.get(1))));

    @SuppressWarnings("unchecked")
    private static Map<String, Double> attributeValues(Object value) { return (Map<String, Double>) value; }

    @Override public ConnectionDirection direction() { return ConnectionDirection.CLIENTBOUND; }
    @Override public void encode(PacketByteBuf buf) { CODEC.encode(this, buf); }
    public static EntityAttributesS2C decode(PacketByteBuf buf) { return CODEC.decode(buf); }
}
