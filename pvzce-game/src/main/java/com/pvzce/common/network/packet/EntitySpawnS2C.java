package com.pvzce.common.network.packet;

import com.pvzce.common.level.SceneBoard;
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
 *
 * <p><b>{@code maxHealth} is this entity's current maximum, not the content
 * definition's number.</b> The definition is not the ceiling: a wave may grow it
 * ({@code healthScale}) and the world's difficulty tier multiplies it, so a client
 * that drew a health bar against the definition would show a hell-tier buckethead as
 * permanently full. This initial value and {@code scale} are projections of its attributes;
 * later changes travel in {@link EntityAttributesS2C}.
 */
public record EntitySpawnS2C(int entityId, String entityKind, String defId, String teamId,
                             float cellX, float cellY, int layer, int health, int maxHealth,
                             String animation, float height, int armor,
                             boolean chilled, float scale, String surfaceId) implements PvzcePacket {
    public EntitySpawnS2C(int entityId, String entityKind, String defId, String teamId,
                             float cellX, float cellY, int layer, int health, int maxHealth,
                             String animation, float height, int armor,
                             boolean chilled, float scale) {
        this(entityId, entityKind, defId, teamId, cellX, cellY, layer, health, maxHealth, animation, height, armor, chilled, scale, SceneBoard.DEFAULT_SURFACE);
    }

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
     * per-<em>entity</em> render-scale attribute on top of it. A miniature zombie and a
     * small sun-shroom's sun use the same mechanism. Nothing the server simulates changes
     * with this presentation attribute.
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
            // What it spawned with: the ceiling for a health bar, and a number the content
            // definition cannot answer (wave growth x difficulty tier).
            .field(EntitySpawnS2C::maxHealth, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(EntitySpawnS2C::animation, PacketByteBuf::writeString, PacketByteBuf::readString)
            .field(EntitySpawnS2C::height, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
            .field(EntitySpawnS2C::armor, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(EntitySpawnS2C::chilled, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
            .field(EntitySpawnS2C::scale, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
            .field(EntitySpawnS2C::surfaceId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .build(values -> new EntitySpawnS2C((Integer) values.get(0), (String) values.get(1),
                    (String) values.get(2), (String) values.get(3), (Float) values.get(4),
                    (Float) values.get(5), (Integer) values.get(6), (Integer) values.get(7),
                    (Integer) values.get(8), (String) values.get(9), (Float) values.get(10),
                    (Integer) values.get(11), (Boolean) values.get(12), (Float) values.get(13), (String) values.get(14)));

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
