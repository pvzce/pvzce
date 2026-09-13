package com.pvzce.client.mechanic;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.gui.hud.cardbar.CardBar;
import com.pvzce.common.network.PacketByteBuf;

/**
 * How one level mechanic looks and behaves on the client.
 *
 * <p>Registered by id, in a second registry beside
 * {@code com.pvzce.common.level.mechanic.LevelMechanic}: the client is a layer above the
 * server's, so the mechanic itself must not reference it. A mechanic with no client
 * registration still works - the board is drawn from the definitions the server sent -
 * it simply has no extra HUD of its own.
 *
 * <p>This is what replaced the client's two hardcoded answers to "is this a belt level":
 * {@code ClientLevel.conveyor()} chose the HUD and {@code PvzceClient.usesConveyorBelt()}
 * chose the entry screen. Both asked about the same mechanic by name, and a second
 * mini-game would have had to add a third and a fourth place to ask.
 */
public interface ClientMechanic {
    Identifier id();

    /** Called once per level instance, with the definition block the server sent. */
    default void onLevelInit(ClientLevel level, com.pvzce.api.content.mechanic.MechanicData data) {
    }

    /**
     * Applies one state update.
     *
     * <p>The payload's fields are declared by the mechanic itself, once, in the
     * {@code PacketStruct.Codec} the server encoded with; this reads through that same
     * declaration rather than restating the field order.
     */
    default void applySync(ClientLevel level, PacketByteBuf payload) {
    }

    /** A card bar this mechanic replaces the ordinary seed bar with, or {@code null}. */
    default CardBar createCardBar(CardBar.Host host) {
        return null;
    }

    /**
     * Extra drawing in world space, on top of the board (the plantable area's red line).
     *
     * <p>Returned per level rather than kept on the mechanic because it is stateful: which
     * zone to draw comes from the level the player is in.
     */
    default WorldOverlay createWorldOverlay(ClientLevel level) {
        return null;
    }

    /** A world-space overlay hook. */
    interface WorldOverlay {
        void render(com.pvzce.client.PvzceClient client, com.pvzce.client.renderer.PvzceCamera camera);
    }
}
