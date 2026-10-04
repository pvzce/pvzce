package com.pvzce.common.network.packet;

import com.pvzce.common.level.SceneBoard;
import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/**
 * A click with nothing in hand on a container: break it open.
 *
 * <p>The mallet is not a tool in this build - a vase is broken by clicking it, and the hammer the
 * player sees is an animation the client plays over that cell (see {@code InGameScreen}'s summoned
 * swing). So the only thing the server has to be told is <em>where</em>: it looks at the cell and
 * answers "a pot of the vase level", "a vase the player placed", or "nothing to break".
 *
 * <p>Its own packet rather than a {@link UseGrantedToolC2S} naming the hammer, because the hammer
 * is deliberately <em>not</em> granted by the vase level any more: a level that granted it would
 * draw a mallet cursor and give the swing a price and a cooldown, and what is being asked for here
 * is the board's own rule - "clicking a vase breaks it" - which holds in every level.
 */
public record SmashContainerC2S(int gridX, int gridY, String surfaceId) implements PvzcePacket {
    public SmashContainerC2S(int gridX, int gridY) {
        this(gridX, gridY, SceneBoard.DEFAULT_SURFACE);
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    public static final PacketStruct.Codec<SmashContainerC2S> CODEC = PacketStruct.<SmashContainerC2S>builder()
    .field(SmashContainerC2S::gridX, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(SmashContainerC2S::gridY, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(SmashContainerC2S::surfaceId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .build(values -> new SmashContainerC2S((Integer) values.get(0), (Integer) values.get(1), (String) values.get(2)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static SmashContainerC2S decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
