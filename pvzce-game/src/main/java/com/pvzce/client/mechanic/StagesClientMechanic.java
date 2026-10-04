package com.pvzce.client.mechanic;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.renderer.PvzceCamera;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mechanic.StagePlan;
import com.pvzce.common.level.mechanic.StagesMechanic;
import com.pvzce.common.network.PacketByteBuf;

/** The stage name, active battle clock and gradual dusk tint are mirrors of server state. */
public final class StagesClientMechanic implements ClientMechanic {
    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_STAGES;
    }

    @Override
    public void applySync(ClientLevel level, PacketByteBuf payload) {
        StagesMechanic.Status state = StagesMechanic.Status.CODEC.decode(payload);
        level.setMechanicState(id(), state);
        level.setActiveBuffs(state.buffs());
    }

    @Override
    public void renderHud(PvzceClient client) {
        StagePlan plan = client.level().mechanicData(id(), StagePlan.class);
        StagesMechanic.Status state = client.level().mechanicStateOrNull(id(), StagesMechanic.Status.class);
        if (plan == null || state == null || state.phase() >= plan.phases().size()) {
            return;
        }
        int seconds = state.battleTicks() / PvzceConstants.TICKS_PER_SECOND;
        String name = GuiLang.raw(plan.phases().get(state.phase()).name(), plan.phases().get(state.phase()).name());
        String text = String.format(GuiLang.raw("gui.pvzce.stages.status", "Stage %d/%d · %s · %d:%02d"),
                state.phase() + 1, plan.phases().size(), name, seconds / 60, seconds % 60);
        float scale = 0.8F;
        float width = client.fonts().body().width(text, scale) + 20F;
        float x = (client.guiWidth() - width) / 2F;
        client.drawSolid(x, 75F, width, 23F, 8F, 0.04F, 0.12F, 0.15F, 0.92F);
        client.fonts().body().draw(text, x + 10F, 82F, scale, 0.8F, 0.95F, 1F, 1F);
    }

    @Override
    public WorldOverlay createWorldOverlay(ClientLevel level) {
        return new WorldOverlay() {
            @Override
            public void render(PvzceClient client, PvzceCamera camera) {
            }

            @Override
            public void renderOver(PvzceClient client, PvzceCamera camera) {
                StagesMechanic.Status state = client.level().mechanicStateOrNull(id(), StagesMechanic.Status.class);
                StagePlan plan = client.level().mechanicData(id(), StagePlan.class);
                if (state != null && plan != null && state.phase() < plan.phases().size()
                        && !plan.phases().get(state.phase()).atmosphere().equals("day")) {
                    boolean finalPhase = plan.phases().get(state.phase()).atmosphere().equals("night");
                    client.drawSolid(-1F, -1F, client.level().width() + 2F, client.level().height() + 5F,
                            2F, finalPhase ? 0.05F : 0.6F, finalPhase ? 0.08F : 0.25F,
                            finalPhase ? 0.25F : 0.05F, finalPhase ? 0.12F : 0.07F);
                }
            }
        };
    }
}
