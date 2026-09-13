package com.pvzce.client.mechanic;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.gui.hud.cardbar.BeltCardBar;
import com.pvzce.client.gui.hud.cardbar.CardBar;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mechanic.ConveyorMechanic;
import com.pvzce.common.network.PacketByteBuf;

/**
 * The conveyor belt, on the client: the tread-surface card bar and the state updates that
 * replace it.
 *
 * <p>Registered by id from {@link ClientMechanics#bootstrap()}. Its server half is
 * {@code ConveyorMechanic}, which builds the {@code BeltCardSource}; the two only share the
 * ids and the state codec, which is why the belt can be reimplemented on one side without
 * the other noticing anything except missing updates.
 */
final class ConveyorClientMechanic implements ClientMechanic {
    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_CONVEYOR;
    }

    @Override
    public CardBar createCardBar(CardBar.Host host) {
        return new BeltCardBar(host);
    }

    /**
     * Replaces the client's card bar with the belt the server just sent.
     *
     * <p>Decoded through {@link ConveyorMechanic.BarState#CODEC}, the same declaration the
     * server encoded with: the field order exists once, so the two sides cannot drift.
     */
    @Override
    public void applySync(ClientLevel level, PacketByteBuf payload) {
        ConveyorMechanic.BarState state = ConveyorMechanic.BarState.CODEC.decode(payload);
        level.replaceSlots(state.cards());
    }
}
