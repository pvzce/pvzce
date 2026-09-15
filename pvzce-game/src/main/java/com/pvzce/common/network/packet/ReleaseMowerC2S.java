package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * The player released a parked lawn mower by hand.
 *
 * <p>The mower has always started itself when a zombie reached the house; this is the other
 * way in, and the original has it too - holding the mouse on a mower sends it early. It is
 * a real decision rather than a convenience: a mower spent on a wave that was already
 * handled is a row that is open for the rest of the level.
 *
 * <p>Only the row travels. <em>Which</em> mower is not the client's to say, and neither is
 * whether it is allowed to go: the server re-checks that the row has a mower that is still
 * parked, so a client that names a row twice gets one launch and a client that names a row
 * that never had a mower gets nothing.
 *
 * @param row the board row whose mower should be sent
 */
public record ReleaseMowerC2S(int row) implements PvzcePacket {
    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeInt(row);
    }

    public static ReleaseMowerC2S decode(PacketByteBuf buf) {
        return new ReleaseMowerC2S(buf.readInt());
    }
}
