package com.pvzce.client.mechanic;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mechanic.RhythmMechanic;
import com.pvzce.common.network.PacketByteBuf;

/**
 * The rhythm chart on the client: one number, and the HUD judges against it.
 *
 * <p>Nothing is drawn here - the notes, the judgement line and the key caps belong to the level's
 * own HUD, and a mechanic that painted a highway of its own would be a second HUD inside the first.
 * What arrives is the chart's <em>anchor</em>: the level tick the first note counts from, which the
 * server takes when the player starts the waves and the client cannot work out for itself (see
 * {@code RhythmMechanic.Start}). It lands in {@code ClientLevel.mechanicState}, where
 * {@code RhythmPlay} reads it once a frame.
 */
public final class RhythmClientMechanic implements ClientMechanic {
    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_RHYTHM;
    }

    @Override
    public void applySync(ClientLevel level, PacketByteBuf payload) {
        level.setMechanicState(PvzceIds.MECHANIC_RHYTHM,
                RhythmMechanic.Start.CODEC.decode(payload));
    }
}
