package com.pvzce.client.mechanic;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.renderer.PvzceCamera;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.level.mechanic.OutpostPlan;
import com.pvzce.common.level.mechanic.OutpostsMechanic;
import com.pvzce.common.level.mechanic.StagesMechanic;
import com.pvzce.common.network.PacketByteBuf;

/** Occupation markers and artillery buttons read one authoritative point list. */
public final class OutpostsClientMechanic implements ClientMechanic {
    @Override
    public Identifier id() {
        return PvzceIds.MECHANIC_OUTPOSTS;
    }

    @Override
    public void applySync(ClientLevel level, PacketByteBuf payload) {
        level.setMechanicState(id(), OutpostsMechanic.Status.CODEC.decode(payload));
    }

    private static float panelWidth(PvzceClient client) {
        return Math.min(500F, client.guiWidth() - 40F);
    }

    public static int clickedArtillery(PvzceClient client, double x, double y) {
        OutpostPlan plan = client.level().mechanicData(PvzceIds.MECHANIC_OUTPOSTS, OutpostPlan.class);
        OutpostsMechanic.Status state = client.level().mechanicStateOrNull(PvzceIds.MECHANIC_OUTPOSTS,
                OutpostsMechanic.Status.class);
        if (plan == null || plan.points().isEmpty() || state == null || client.level().preparing() || y < 38F || y > 67F) {
            return -1;
        }
        float width = panelWidth(client);
        float left = (client.guiWidth() - width) / 2F;
        if (x < left || x >= left + width || state.points().size() != plan.points().size()) {
            return -1;
        }
        int index = (int) ((x - left) * plan.points().size() / width);
        OutpostsMechanic.PointStatus point = state.points().get(index);
        return point.owned() && plan.points().get(index).charges() > 0 ? index : -1;
    }

    @Override
    public void renderHud(PvzceClient client) {
        OutpostPlan plan = client.level().mechanicData(id(), OutpostPlan.class);
        OutpostsMechanic.Status state = client.level().mechanicStateOrNull(id(), OutpostsMechanic.Status.class);
        if (plan == null || state == null || plan.points().isEmpty()) {
            return;
        }
        float width = panelWidth(client);
        float left = (client.guiWidth() - width) / 2F;
        float part = width / plan.points().size();
        for (int i = 0; i < Math.min(plan.points().size(), state.points().size()); i++) {
            OutpostPlan.Point point = plan.points().get(i);
            OutpostsMechanic.PointStatus owned = state.points().get(i);
            float x = left + i * part;
            client.drawSolid(x, 38F, part - 4F, 29F, 8F, 0.04F, owned.owned() ? 0.25F : 0.12F, 0.18F, 0.94F);
            String name = GuiLang.raw(point.name(), point.name());
            String detail = client.level().currentWave() < point.fromWave()
                    ? String.format(GuiLang.raw("gui.pvzce.outposts.locked", "%s · opens at wave %d"), name, point.fromWave())
                    : owned.owned()
                    ? point.charges() > 0
                        ? String.format(GuiLang.raw("gui.pvzce.outposts.fire", "%s · strike (%d)"), name, owned.charges())
                        : String.format(GuiLang.raw("gui.pvzce.outposts.owned", "%s · supplies"), name)
                    : String.format(GuiLang.raw("gui.pvzce.outposts.capture", "%s · capture %d%%"), name,
                            Math.min(100, owned.progress() * 100 / Math.max(1, point.captureTicks())));
            float scale = Math.min(0.75F, (part - 16F) / Math.max(1F, client.fonts().body().width(detail, 1F)));
            client.fonts().body().draw(detail, x + 8F, 48F, scale, 0.85F, 1F, 0.85F, 1F);
            client.drawSolid(x, 38F, (part - 4F) * owned.progress() / Math.max(1, point.captureTicks()), 2F,
                    8.1F, 0.25F, 0.9F, 0.65F, 1F);
        }
        StagesMechanic.Status stage = client.level().mechanicStateOrNull(PvzceIds.MECHANIC_STAGES,
                StagesMechanic.Status.class);
        String hint = GuiLang.raw(stage != null && stage.ready()
                ? "gui.pvzce.outposts.finish" : "gui.pvzce.outposts.hint", "Hold outposts with a plant; click artillery to aim");
        float scale = Math.min(0.72F, width / Math.max(1F, client.fonts().body().width(hint, 1F)));
        client.fonts().body().draw(hint, (client.guiWidth() - client.fonts().body().width(hint, scale)) / 2F,
                22F, scale, 0.8F, 0.95F, 1F, 1F);
    }

    @Override
    public WorldOverlay createWorldOverlay(ClientLevel level) {
        return new WorldOverlay() {
            @Override
            public void render(PvzceClient client, PvzceCamera camera) {
            }

            @Override
            public void renderOver(PvzceClient client, PvzceCamera camera) {
                OutpostPlan plan = client.level().mechanicData(id(), OutpostPlan.class);
                OutpostsMechanic.Status state = client.level().mechanicStateOrNull(id(), OutpostsMechanic.Status.class);
                if (plan == null || state == null) {
                    return;
                }
                for (int i = 0; i < Math.min(plan.points().size(), state.points().size()); i++) {
                    OutpostPlan.Point point = plan.points().get(i);
                    if (!point.surface().equals(client.level().activeSurface())) {
                        continue;
                    }
                    boolean owned = state.points().get(i).owned();
                    float elevation = client.level().sceneBoard().elevationAt(point.surface(), point.x() + 0.5F,
                            point.y() + 0.5F);
                    client.drawSolid(point.x() + 0.06F, point.y() + elevation + 0.06F, 0.88F, 0.88F,
                            0.1F, owned ? 0.1F : 0.95F, owned ? 0.8F : 0.65F, 0.2F, 0.35F);
                }
                plan.goal().filter(goal -> goal.surface().equals(client.level().activeSurface())).ifPresent(goal -> {
                    float elevation = client.level().sceneBoard().elevationAt(goal.surface(), goal.x() + 0.5F,
                            goal.y() + 0.5F);
                    client.drawSolid(goal.x() + 0.1F, goal.y() + elevation + 0.1F, 0.8F, 0.8F,
                            0.1F, 0.9F, 0.15F, 0.1F, 0.65F);
                });
            }
        };
    }
}
