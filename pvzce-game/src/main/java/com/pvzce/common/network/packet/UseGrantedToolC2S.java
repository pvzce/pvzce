package com.pvzce.common.network.packet;

import com.pvzce.common.level.SceneBoard;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.network.ConnectionDirection;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.PvzcePacket;

/**
 * Swing a tool the <em>level</em> grants, at a board cell.
 *
 * <p>Whack-a-Zombie's mallet: a level that declares {@code pvzce:tool} with {@code "default":
 * true} makes a click with no card in hand use that tool, and there is no card to name. Naming
 * the tool by id rather than by slot is the whole point - a default tool has no slot, no price
 * on a card and no recharge bar, so there is nothing in the card bar for the server to look up.
 *
 * <p>Kept separate from {@link UseToolC2S} rather than added as a {@code slotIndex} sentinel:
 * "which card" and "which granted tool" are different questions, and a slot index of -1 would
 * be a third meaning for a field that already has "an index" and "a belt card id".
 */
public record UseGrantedToolC2S(Identifier tool, int gridX, int gridY, String surfaceId) implements PvzcePacket {
    public UseGrantedToolC2S(Identifier tool, int gridX, int gridY) {
        this(tool, gridX, gridY, SceneBoard.DEFAULT_SURFACE);
    }

    @Override
    public ConnectionDirection direction() {
        return ConnectionDirection.SERVERBOUND;
    }

    public static final PacketStruct.Codec<UseGrantedToolC2S> CODEC = PacketStruct.<UseGrantedToolC2S>builder()
    .field(UseGrantedToolC2S::tool, PacketByteBuf::writeIdentifier, PacketByteBuf::readIdentifierOrNull)
    .field(UseGrantedToolC2S::gridX, PacketByteBuf::writeInt, PacketByteBuf::readInt)
    .field(UseGrantedToolC2S::gridY, PacketByteBuf::writeInt, PacketByteBuf::readInt)
            .field(UseGrantedToolC2S::surfaceId, PacketByteBuf::writeString, PacketByteBuf::readString)
            .build(values -> new UseGrantedToolC2S((Identifier) values.get(0), (Integer) values.get(1), (Integer) values.get(2), (String) values.get(3)));

    @Override
    public void encode(PacketByteBuf buf) {
        CODEC.encode(this, buf);
    }

    public static UseGrantedToolC2S decode(PacketByteBuf buf) {
        return CODEC.decode(buf);
    }
}
