package com.pvzce.client.mechanic;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.level.mechanic.PreparationMechanic;

/**
 * The preparation phase on the client: one flag, and the HUD reads it.
 *
 * <p>Nothing is drawn here. The start button and the phase's hint belong to the level HUD - they
 * sit with the wave meter and the card bar, and a mechanic that painted its own button would be a
 * second HUD inside the first - so this half is only the state: {@code ClientLevel.preparing()}.
 * The button itself asks the server ({@code StartWavesC2S}) rather than clearing the flag locally,
 * because the server is what decides whether the phase is still running.
 */
public final class PreparationClientMechanic implements ClientMechanic {
    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_PREPARATION;
    }

    @Override
    public void applySync(ClientLevel level, PacketByteBuf payload) {
        PreparationMechanic.State state = PreparationMechanic.State.CODEC.decode(payload);
        level.setPreparing(state.preparing());
    }
}
