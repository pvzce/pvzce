package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/**
 * Full entity state for a newly streamed (or re-streamed) entity: the same field
 * set as {@link EntityUpdateS2C} plus the identity fields.
 *
 * <p>Carrying animation and height here (not only in the update packet) is what
 * stops a full-state resync from popping every entity back to {@code idle} at
 * height 0 for up to three ticks. The grid column/row are deliberately absent:
 * both sides derive them from the cell position and the level's own size through
 * the shared entity base, so sending them would be a second, divergent source.
 */
public record EntitySpawnS2C(int entityId, String entityKind, String defId, String teamId,
                             float cellX, float cellY, int layer, int health,
                             String animation, float height, int armor,
                             boolean chilled, float scale) implements PvzcePacket {
    /**
     * The armour value of an entity that has none.
     *
     * <p>{@code 0} means "was wearing armour, and it is gone" - a Conehead whose cone was
     * shot off - which is not the same thing as a zombie that never had a cone. The client
     * draws those two differently (see {@code EquipmentArt}), so the wire says which one
     * it is.
     */
    public static final int NO_ARMOR = -1;

    /**
     * The scale of an entity that is drawn at exactly the size its definition declares.
     *
     * <p>{@code render_scale} keeps living in the content definition and keeps <em>not</em>
     * travelling, because both sides load the same data pack. This field is the
     * per-<em>entity</em> multiplier on top of it, and it exists for the drops that are one
     * resource at two sizes: a small sun-shroom's sun is worth 15 and is drawn smaller than
     * the 25 the sky drops. Nothing the server simulates changes with it.
     */
    public static final float DEFAULT_SCALE = 1F;

    public static final PacketStruct.Codec<EntitySpawnS2C> CODEC = PacketStruct.<EntitySpawnS2C>builder()
            .field(EntitySpawnS2C::entityId, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(EntitySpawnS2C::entityKind, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(EntitySpawnS2C::defId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(EntitySpawnS2C::teamId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(EntitySpawnS2C::cellX, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
            .field(EntitySpawnS2C::cellY, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
            .field(EntitySpawnS2C::layer, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(EntitySpawnS2C::health, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(EntitySpawnS2C::animation, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(EntitySpawnS2C::height, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
            .field(EntitySpawnS2C::armor, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(EntitySpawnS2C::chilled, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            .field(EntitySpawnS2C::scale, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
            .build(values -> new EntitySpawnS2C((Integer) values.get(0), (String) values.get(1),
                    (String) values.get(2), (String) values.get(3), (Float) values.get(4),
                    (Float) values.get(5), (Integer) values.get(6), (Integer) values.get(7),
                    (String) values.get(8), (Float) values.get(9), (Integer) values.get(10),
                    (Boolean) values.get(11), (Float) values.get(12)));

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static EntitySpawnS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
