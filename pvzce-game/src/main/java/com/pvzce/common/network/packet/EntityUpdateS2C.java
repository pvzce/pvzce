package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
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
                              String teamId) implements PvzcePacket {
    /** {@code teamId} unwritten: this entity has not changed sides since the spawn packet. */
    public static final String NO_TEAM = "";

    /** The common case: an update that carries no side change and no charm. */
    public EntityUpdateS2C(int entityId, float cellX, float cellY, int health, String animation,
                           float height, int armor, boolean chilled) {
        this(entityId, cellX, cellY, health, animation, height, armor, chilled, false, NO_TEAM);
    }

    /** An update with the status flags but no side change. */
    public EntityUpdateS2C(int entityId, float cellX, float cellY, int health, String animation,
                           float height, int armor, boolean chilled, boolean charmed) {
        this(entityId, cellX, cellY, health, animation, height, armor, chilled, charmed, NO_TEAM);
    }

    public EntityUpdateS2C {
        teamId = teamId == null ? NO_TEAM : teamId;
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.CLIENTBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeFloat(cellX);
        buf.writeFloat(cellY);
        buf.writeInt(health);
        buf.writeString(animation);
        buf.writeFloat(height);
        buf.writeInt(armor);
        buf.writeBoolean(chilled);
        buf.writeBoolean(charmed);
        buf.writeString(teamId);
    }

    public static EntityUpdateS2C decode(PacketByteBuf buf) {
        return new EntityUpdateS2C(buf.readInt(), buf.readFloat(), buf.readFloat(), buf.readInt(),
                buf.readString(), buf.readFloat(), buf.readInt(), buf.readBoolean(),
                buf.readBoolean(), buf.readString());
    }
}
