package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * One move from the player flying Dr. Zomboss's machine: lower or raise the head, spit a ball, or one
 * of the machine's attacks aimed at a cell.
 *
 * <p>The move travels by name ({@code ZombossMechanic.PilotMove}) and the cell is where the player
 * aimed; the moves that are not aimed send it too and the server ignores it. Nothing else is the
 * client's to say: the side, the recharge, whether the head is down and what the attack hits are all
 * decided by the server, and a refusal comes back as a language key.
 *
 * @param action the move's name
 * @param gridX  the aimed column
 * @param gridY  the aimed row
 */
public record ZombossCommandC2S(String action, int gridX, int gridY) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(action);
        buf.writeInt(gridX);
        buf.writeInt(gridY);
    }

    public static ZombossCommandC2S decode(PacketByteBuf buf) {
        return new ZombossCommandC2S(buf.readString(), buf.readInt(), buf.readInt());
    }
}
