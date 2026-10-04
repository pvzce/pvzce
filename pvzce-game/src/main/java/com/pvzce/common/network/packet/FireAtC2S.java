package com.pvzce.common.network.packet;

import com.pvzce.common.level.SceneBoard;
import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * Fire a hand-aimed plant at a cell: the cob cannon's click.
 *
 * <p><b>Both the plant and the cell travel, and neither is trusted.</b> The entity id is how the
 * client says <em>which</em> cannon - a lawn can have six of them and "the one under the cursor" is
 * the client's answer, the same way {@code PickUpCardC2S} names a packet rather than a cell. The
 * cell is where the player aimed. What the server then decides is everything that matters: that the
 * entity is a plant, that it is on the sender's team, whether it is loaded, and whether the cell is
 * on the board. A client that sends this twice in a tick fires one cob, because the first one
 * unloads the cannon.
 *
 * <p>There is no "aiming" state on the wire. Aiming is the reticle the client draws between the two
 * clicks, and it has no server-side meaning - exactly like {@code PickCardC2S}, which the server
 * reads and ignores.
 */
public record FireAtC2S(int entityId, int gridX, int gridY, String surfaceId) implements PvzcePacket {
    public FireAtC2S(int entityId, int gridX, int gridY) {
        this(entityId, gridX, gridY, SceneBoard.DEFAULT_SURFACE);
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeInt(entityId);
        buf.writeInt(gridX);
        buf.writeInt(gridY);
        buf.writeString(surfaceId);
    }

    public static FireAtC2S decode(PacketByteBuf buf) {
        return new FireAtC2S(buf.readInt(), buf.readInt(), buf.readInt(), buf.readString());
    }
}
