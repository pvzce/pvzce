package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/**
 * A streamed entity's changed state.
 *
 * <p>{@code armor} is {@link EntitySpawnS2C#NO_ARMOR} for anything that wears none, and
 * the remaining armour otherwise (0 = it was shot off). The client needs it to draw the
 * right cone on a Conehead; nothing about it is simulated on that side.
 *
 * <p>{@code chilled} says the zombie is currently under a {@code slow} status, and
 * {@code charmed} that it has been turned against its own side (by the hypno-shroom). Both
 * travel for the same reason the armour does: the client draws them and can work neither out
 * from anything else - a slowed zombie's position is its own business, and a charmed one's side
 * is server state. Only zombies ever answer either true, which is why they are booleans on the
 * one entity update rather than a status list.
 *
 * <p>{@link #NO_TEAM} in {@code teamId} means "unchanged": a side only moves when something
 * moves it, so the string travels on the ticks after a charm and not again. It is here so a
 * client whose first sight of a zombie is an update (not the spawn packet) still knows which
 * side it is drawing.
 */
public record EntityUpdateS2C(int entityId, float cellX, float cellY, int health, String animation,
                              float height, int armor, boolean chilled, boolean charmed,
                              boolean frozen, boolean buttered, int animationSequence, String teamId) implements PvzcePacket {
    /** {@code teamId} unwritten: this entity has not changed sides since the spawn packet. */
    public static final String NO_TEAM = "";

    /** The common case: an update that carries no side change and no charm. */
    public EntityUpdateS2C(int entityId, float cellX, float cellY, int health, String animation,
                           float height, int armor, boolean chilled) {
        this(entityId, cellX, cellY, health, animation, height, armor, chilled, false, false, false, 0, NO_TEAM);
    }

    /** An update with the status flags but no side change. */
    public EntityUpdateS2C(int entityId, float cellX, float cellY, int health, String animation,
                           float height, int armor, boolean chilled, boolean charmed) {
        this(entityId, cellX, cellY, health, animation, height, armor, chilled, charmed, false, false, 0, NO_TEAM);
    }

    /** An update with every status flag and no side change. */
    public EntityUpdateS2C(int entityId, float cellX, float cellY, int health, String animation,
                           float height, int armor, boolean chilled, boolean charmed, boolean frozen) {
        this(entityId, cellX, cellY, health, animation, height, armor, chilled, charmed, frozen, false, 0, NO_TEAM);
    }

    public EntityUpdateS2C(int entityId, float cellX, float cellY, int health, String animation,
                           float height, int armor, boolean chilled, boolean charmed,
                           boolean frozen, String teamId) {
        this(entityId, cellX, cellY, health, animation, height, armor, chilled, charmed, frozen, false, 0, teamId);
    }

    public EntityUpdateS2C {
        teamId = teamId == null ? NO_TEAM : teamId;
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    public static final PacketStruct.Codec<EntityUpdateS2C> CODEC = PacketStruct.<EntityUpdateS2C>builder()
    .field(EntityUpdateS2C::entityId, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(EntityUpdateS2C::cellX, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
    .field(EntityUpdateS2C::cellY, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
    .field(EntityUpdateS2C::health, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(EntityUpdateS2C::animation, PacketByteBuf::writeString, PacketByteBuf::readString)
    .field(EntityUpdateS2C::height, PacketByteBuf::writeFloat, PacketByteBuf::readFloat)
    // 0 (worn out) and -1 (never had any) are different answers, so armour is not a boolean.
    .field(EntityUpdateS2C::armor, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(EntityUpdateS2C::chilled, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
    .field(EntityUpdateS2C::charmed, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
    // Held solid by the ice-shroom: the client stops the clip and draws the ice, neither of
    // which it can work out from "slowed" (which only means the speed is scaled).
    .field(EntityUpdateS2C::frozen, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
    .field(EntityUpdateS2C::buttered, PacketByteBuf::writeBoolean, PacketByteBuf::readBoolean)
    .field(EntityUpdateS2C::animationSequence, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(EntityUpdateS2C::teamId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .build(values -> new EntityUpdateS2C((Integer) values.get(0), (Float) values.get(1), (Float) values.get(2), (Integer) values.get(3), (String) values.get(4), (Float) values.get(5), (Integer) values.get(6), (Boolean) values.get(7), (Boolean) values.get(8), (Boolean) values.get(9), (Boolean) values.get(10), (Integer) values.get(11), (String) values.get(12)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static EntityUpdateS2C decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
