package com.pvzce.client.mechanic;

import com.pvzce.api.content.PlantGardenData;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mechanic.IZombieGarden;
import com.pvzce.common.network.PacketByteBuf;

/** Red deployment line and the five server-owned brains. */
public final class PlantGardenClientMechanic implements ClientMechanic {
    private static final Identifier BRAIN = PvzceIds.id("textures/gui/hud/i_zombie_brain");

    @Override public Identifier id() { return PvzceIds.MECHANIC_PLANT_GARDEN; }

    public static boolean cardboard(ClientLevel level, ClientEntity entity) {
        PlantGardenData data = level.mechanicData(PvzceIds.MECHANIC_PLANT_GARDEN, PlantGardenData.class);
        return data != null && data.brains() && EntityKind.PLANT.equals(entity.kind());
    }

    @Override public void applySync(ClientLevel level, PacketByteBuf payload) {
        level.setMechanicState(id(), IZombieGarden.Progress.CODEC.decode(payload));
    }

    @Override public WorldOverlay createWorldOverlay(ClientLevel level) {
        PlantGardenData data = level.mechanicData(id(), PlantGardenData.class);
        if (!data.brains()) return null;
        return (client, camera) -> {
            client.drawSolid(data.columns() - 0.03F, 0, 0.06F, level.height(), 0.19F, 0.85F, 0.05F, 0.05F, 0.9F);
            IZombieGarden.Progress progress = level.mechanicStateOrNull(id(), IZombieGarden.Progress.class);
            int eaten = progress == null ? 0 : progress.eatenRows();
            for (int row = 0; row < level.height(); row++) {
                if ((eaten & (1 << row)) == 0)
                    client.drawTexture(BRAIN, -0.72F, row + 0.23F, 0.62F, 0.54F, 0.2F, 1F, 1F, 1F, 1F);
            }
        };
    }

    @Override public void renderHud(PvzceClient client) {
        PlantGardenData data = client.level().mechanicData(id(), PlantGardenData.class);
        if (!data.brains()) return;
        IZombieGarden.Progress progress = client.level().mechanicStateOrNull(id(), IZombieGarden.Progress.class);
        if (progress == null) return;
        String text = String.format(GuiLang.raw("gui.pvzce.i_zombie.brains", "脑子 %d / %d"),
                Integer.bitCount(progress.eatenRows()), client.level().height());
        if (data.endless()) text += " · " + String.format(GuiLang.raw("gui.pvzce.i_zombie.round", "已完成 %d 轮"), progress.round());
        float width = client.fonts().body().width(text, 0.8F);
        client.drawSolid(client.guiWidth() - width - 24, 12, width + 16, 23, 8F, 0.12F, 0.06F, 0.04F, 0.85F);
        client.fonts().body().draw(text, client.guiWidth() - width - 16, 19, 0.8F, 1F, 0.85F, 0.7F, 1F);
    }
}
