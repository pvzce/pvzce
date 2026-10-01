package com.pvzce.client.mechanic;

import com.pvzce.api.util.Identifier;
import com.pvzce.api.content.ResonanceData;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mechanic.ResonanceMechanic.Status;
import com.pvzce.common.network.PacketByteBuf;

/** The server's row and countdown, with the active lane and the next-lane warning. */
public final class ResonanceClientMechanic implements ClientMechanic {
    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_RESONANCE;
    }

    @Override
    public void applySync(ClientLevel level, PacketByteBuf payload) {
        level.setMechanicState(id(), Status.CODEC.decode(payload));
    }

    @Override
    public WorldOverlay createWorldOverlay(ClientLevel level) {
        return (client, camera) -> {
            Status status = status(client.level());
            if (status == null) {
                return;
            }
            int width = client.level().width();
            // World rows increase from the bottom. All geometry reads the packet's row, so the
            // painted band and the accelerated zombies cannot select opposite lanes.
            client.drawSolid(0, status.row(), width, 1, 0.04F, 0.2F, 0.9F, 0.75F, 0.20F);
            client.drawSolid(0, status.row(), width, 0.035F, 0.05F, 0.4F, 1F, 0.85F, 0.8F);
            client.drawSolid(0, status.row() + 0.965F, width, 0.035F, 0.05F, 0.4F, 1F, 0.85F, 0.8F);
            if (status.ticksLeft() <= 3 * PvzceConstants.TICKS_PER_SECOND) {
                for (int x = 0; x < width; x++) {
                    client.drawSolid(x + 0.15F, status.nextRow() + 0.45F, 0.7F, 0.08F,
                            0.05F, 1F, 0.8F, 0.25F, 0.55F);
                }
            }
        };
    }

    private static Status status(ClientLevel level) {
        return level.mechanicStateOrNull(PvzceIds.MECHANIC_RESONANCE, Status.class);
    }

    @Override
    public void renderHud(PvzceClient client) {
        Status status = status(client.level());
        ResonanceData data = client.level().mechanicData(PvzceIds.MECHANIC_RESONANCE, ResonanceData.class);
        if (status == null || data == null) {
            return;
        }
        // Row labels count from the top, as a player reading the lawn would count them.
        int current = client.level().height() - status.row();
        int next = client.level().height() - status.nextRow();
        int seconds = (status.ticksLeft() + PvzceConstants.TICKS_PER_SECOND - 1)
                / PvzceConstants.TICKS_PER_SECOND;
        String text = String.format(GuiLang.raw("gui.pvzce.resonance.status",
                "Resonance row %d · next row in %ds: %d"), current, seconds, next);
        String rule = String.format(GuiLang.raw("gui.pvzce.resonance.rule",
                "%d%% faster zombies · lily attack rate ×%.1f"),
                Math.round((data.zombieSpeed() - 1F) * 100F), data.plantRate());
        float scale = 0.8F;
        float width = Math.max(client.fonts().body().width(text, scale),
                client.fonts().body().width(rule, scale)) + 16F;
        float x = (client.guiWidth() - width) / 2F;
        float y = 16F;
        client.drawSolid(x, y, width, 30F, 8F, 0.03F, 0.16F, 0.13F, 0.85F);
        client.fonts().body().draw(text, x + 8F, y + 18F, scale, 0.6F, 1F, 0.85F, 1F);
        client.fonts().body().draw(rule, x + 8F, y + 6F, scale, 1F, 0.85F, 0.4F, 1F);
        client.drawSolid(x, y, width * status.ticksLeft() / status.intervalTicks(), 2F,
                8.1F, 0.3F, 0.95F, 0.8F, 1F);
    }
}
