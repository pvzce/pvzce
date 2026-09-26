package com.pvzce.common.network.packet;

import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PvzcePacket;

/**
 * One line the player typed into the chat, for the server to say back.
 *
 * <p>It goes to the server rather than being appended to the client's own log, which would be
 * simpler and wrong: a message the server never saw is not in anyone else's log, and the one place
 * this game is a client of a server is exactly the place multiplayer would add a second player to.
 * The server answers with an ordinary {@code ServerMessageS2C}, so the line appears through the
 * same path as every other message the game prints.
 *
 * <p>No name travels with it: which world (and so whose save) is talking is the connection's own
 * fact, and a name in the packet would be a name a modified client could choose.
 */
public record ChatC2S(String text) implements PvzcePacket {
    /** The longest line the server will say back; longer text is cut rather than refused. */
    public static final int MAX_LENGTH = 256;

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    @Override
    public void encode(PacketByteBuf buf) {
        buf.writeString(text == null ? "" : text);
    }

    public static ChatC2S decode(PacketByteBuf buf) {
        return new ChatC2S(buf.readString());
    }
}
