package com.pvzce.client.mechanic;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.SeedCardRenderer;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.PlantRecipes;
import com.pvzce.common.level.FusionState;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.packet.FusionActionC2S;
import com.pvzce.common.network.packet.PickUpCardC2S;
import com.pvzce.common.network.packet.ReleaseHeldCardC2S;
import com.pvzce.common.network.packet.UseGrantedToolC2S;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** A permanent workshop below the camera viewport, with the same geometry for paint and input. */
public final class FusionClientMechanic implements ClientMechanic {
    private static final float WIDTH = 960F, HEIGHT = 168F;
    private static final int PAGE_SIZE = 12;
    private static final class View {
        FusionState state;
        boolean shovel;
        int page, seedPage, trayPage;
        String notice = "";
        long noticeUntil;
    }
    private static View view(ClientLevel level) {
        return level.mechanicState(PvzceIds.MECHANIC_FUSION, View::new);
    }
    public static boolean active(ClientLevel level) { return level.hasMechanic(PvzceIds.MECHANIC_FUSION); }
    private static float scale(PvzceClient client) {
        return Math.min(client.guiWidth() / WIDTH, client.guiHeight() / 540F);
    }
    public static int reservedPixels(PvzceClient client) {
        return active(client.level()) ? Math.round(HEIGHT * scale(client) * client.window().height() / client.guiHeight()) : 0;
    }
    public static float[] sunTarget(PvzceClient client) {
        float s = scale(client);
        return new float[]{(client.guiWidth() - WIDTH * s) / 2F + 52 * s, 106 * s};
    }
    @Override public Identifier id() { return PvzceIds.MECHANIC_FUSION; }
    @Override public void applySync(ClientLevel level, PacketByteBuf payload) {
        view(level).state = FusionState.CODEC.decode(payload);
    }
    public static void showNotice(ClientLevel level, String message) {
        if (!active(level)) return;
        View view = view(level); view.notice = message;
        view.noticeUntil = System.nanoTime() + 4_000_000_000L;
    }
    private static List<Identifier> abilities(View view) {
        List<Identifier> result = new ArrayList<>(PlantRecipes.abilities());
        List<String> first = List.of("shooter", "producer", "defense", "carrier", "explosive", "thrower", "nocturnal", "melee");
        result.sort(Comparator.<Identifier>comparingInt(id -> first.indexOf(id.path()) < 0 ? first.size() : first.indexOf(id.path()))
                .thenComparing(Identifier::toString));
        return result;
    }
    private static int count(List<FusionState.Count> counts, String ability) {
        return counts.stream().filter(c -> c.ability().equals(ability)).mapToInt(FusionState.Count::amount).sum();
    }
    private static List<ClientEntity> seeds(PvzceClient client) {
        return client.level().entities().values().stream().filter(e -> "card_drop".equals(e.kind())
                && e.id() != client.level().heldCardEntityId()).sorted(Comparator.comparingInt(ClientEntity::id)).toList();
    }
    private static String name(String ability) {
        Identifier id = Identifier.tryParse(ability);
        if (id == null) return ability;
        String key = "plant_capability." + id.namespace() + "." + id.path();
        String result = GuiLang.raw(key, ability);
        return result.equals(ability) ? GuiLang.raw("capability." + id.namespace() + "." + id.path(), ability) : result;
    }
    private static void send(PvzceClient client, String action, String ability, int drop) {
        client.connection().send(new FusionActionC2S(action, ability, drop));
    }
    private static void rect(PvzceClient c, float x, float y, float w, float h, float r, float g, float b) {
        float s = scale(c), left = (c.guiWidth() - WIDTH * s) / 2F;
        c.drawSolid(left + x * s, y * s, w * s, h * s, 8F, r, g, b, 1F);
    }
    private static void text(PvzceClient c, String value, float x, float y, float size, float r, float g, float b) {
        float s = scale(c), left = (c.guiWidth() - WIDTH * s) / 2F;
        c.fonts().body().draw(value, left + x * s, y * s, size * s, r, g, b, 1F);
    }
    private static void fitText(PvzceClient c, String value, float x, float y, float width, float r, float g, float b) {
        float size = Math.min(0.72F, width / Math.max(1F, c.fonts().body().width(value, 1F)));
        text(c, value, x, y, size, r, g, b);
    }
    private static void button(PvzceClient c, String label, float x, float y, float w, float h, boolean enabled) {
        rect(c, x, y, w, h, enabled ? 0.20F : 0.13F, enabled ? 0.42F : 0.23F, enabled ? 0.35F : 0.22F);
        fitText(c, label, x + 7, y + h / 2F - 4, w - 14, enabled ? 0.96F : 0.52F, enabled ? 0.91F : 0.60F, 0.73F);
    }
    @Override public void renderHud(PvzceClient c) {
        View v = view(c.level()); FusionState state = v.state;
        if (state == null) return;
        var data = c.level().mechanicData(id(), com.pvzce.api.content.FusionData.class);
        int lossChance = Math.round(100F * data.lossChance()), dropChance = Math.round(100F * data.dropChance());
        float s = scale(c), left = (c.guiWidth() - WIDTH * s) / 2F;
        c.drawSolid(0, 0, c.guiWidth(), HEIGHT * s, 8F, 0.055F, 0.12F, 0.12F, 1F);
        rect(c, 0, 165, WIDTH, 3, 0.82F, 0.66F, 0.32F);
        int wave = c.level().currentWave(), total = c.level().totalWaves();
        float progress = total <= 0 ? 0F : Math.min(1F, (wave + c.level().waveProgress()) / total);
        rect(c, 0, 165, WIDTH * progress, 3, 0.35F, 0.80F, 0.65F);
        rect(c, 108, 8, 1, 130, 0.25F, 0.36F, 0.30F);
        rect(c, 460, 8, 1, 130, 0.25F, 0.36F, 0.30F);
        rect(c, 756, 8, 1, 130, 0.25F, 0.36F, 0.30F);
        String[] lesson = {"01  选择分解铲，再点击草坪上的一株豌豆射手（这一次不会损耗）",
                "02  点击库存中的“射手”，把一份能力放入合成区",
                "03  点击“合成植物”：相同能力的背包植物会随机出现一种",
                "04  点击待种卡片，再点击可种植的草坪格子；完成后开始迎战"};
        String line = !state.success() ? state.message() : state.tutorialStep() < 4 ? lesson[state.tutorialStep()] : state.message().isEmpty()
                ? "击败僵尸有" + dropChance + "%概率掉落能力 · 点击库存放入，点击合成区撤回 · 右侧 + 用阳光购买" : state.message();
        if (System.nanoTime() < v.noticeUntil) line = v.notice;
        fitText(c, line, 16, 147, 832, state.success() ? 0.90F : 1F, state.success() ? 0.92F : 0.55F, 0.65F);
        text(c, "波次 " + wave + "/" + total, 855, 147, 0.75F, 0.80F, 0.9F, 0.78F);
        text(c, "融合工坊", 12, 121, 0.85F, 0.98F, 0.83F, 0.48F);
        text(c, "阳光  " + c.level().sun(), 12, 101, 0.80F, 1F, 0.91F, 0.51F);
        rect(c, 12, 28, 84, 62, 0.20F, 0.42F, 0.35F);
        fitText(c, v.shovel ? "分解中 [S]" : "分解铲 [S]", 19, 38, 70, 0.96F, 0.91F, 0.73F);
        if (v.shovel) rect(c, 12, 28, 84, 2, 1F, 0.72F, 0.28F);
        c.drawTexture(PvzceIds.id("textures/entities/tool/shovel"), left + 40 * s, 55 * s, 29 * s, 29 * s, 8.1F, 1F, 1F, 1F, 1F);
        text(c, "损耗：" + lossChance + "% / 一份", 12, 12, 0.62F, 0.65F, 0.77F, 0.70F);
        text(c, "能力库存", 120, 123, 0.8F, 0.87F, 0.95F, 0.85F);
        text(c, "+ 购买 / " + state.price() + " 阳光", 290, 123, 0.66F, 0.85F, 0.76F, 0.53F);
        List<Identifier> ids = abilities(v); int pages = Math.max(1, (ids.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        v.page = Math.min(v.page, pages - 1);
        for (int i = 0; i < PAGE_SIZE && v.page * PAGE_SIZE + i < ids.size(); i++) {
            String id = ids.get(v.page * PAGE_SIZE + i).toString();
            float x = 120 + i % 4 * 84, y = 86 - i / 4 * 27;
            int amount = count(state.inventory(), id);
            rect(c, x, y, 61, 24, amount > 0 ? 0.23F : 0.12F, amount > 0 ? 0.35F : 0.22F, 0.25F);
            fitText(c, name(id), x + 4, y + 13, 54, amount > 0 ? 0.95F : 0.55F, 0.86F, 0.66F);
            text(c, "×" + amount, x + 4, y + 3, 0.57F, 0.70F, 0.84F, 0.76F);
            button(c, "+", x + 63, y, 18, 24, c.level().sun() >= state.price());
        }
        button(c, "‹", 120, 8, 25, 19, v.page > 0);
        text(c, (v.page + 1) + " / " + pages, 155, 14, 0.65F, 0.7F, 0.8F, 0.74F);
        button(c, "›", 204, 8, 25, 19, v.page < pages - 1);
        button(c, "收集掉落 (" + state.drops().size() + ")", 242, 8, 207, 19, !state.drops().isEmpty());
        text(c, "合成区", 474, 123, 0.8F, 0.98F, 0.86F, 0.55F);
        text(c, "点击能力即可撤回", 581, 123, 0.65F, 0.65F, 0.79F, 0.72F);
        int trayPages = Math.max(1, (state.tray().size() + 5) / 6); v.trayPage = Math.min(v.trayPage, trayPages - 1);
        rect(c, 473, 52, 270, 59, 0.10F, 0.20F, 0.19F);
        if (state.tray().isEmpty()) fitText(c, "从左侧库存选择能力，尝试新的组合", 486, 78, 246, 0.58F, 0.70F, 0.64F);
        for (int i = 0; i < 6 && v.trayPage * 6 + i < state.tray().size(); i++) {
            FusionState.Count entry = state.tray().get(v.trayPage * 6 + i);
            button(c, name(entry.ability()) + " ×" + entry.amount(), 478 + i % 3 * 87, 82 - i / 3 * 26, 82, 23, true);
        }
        button(c, "合成植物", 474, 8, 140, 34, !state.tray().isEmpty());
        button(c, "全部返还", 621, 8, 91, 34, !state.tray().isEmpty());
        button(c, "›", 718, 8, 25, 34, trayPages > 1);
        text(c, "待种卡片", 770, 123, 0.8F, 0.87F, 0.95F, 0.85F);
        List<ClientEntity> seeds = seeds(c); int seedPages = Math.max(1, (seeds.size() + 2) / 3); v.seedPage = Math.min(v.seedPage, seedPages - 1);
        for (int i = 0; i < 3 && v.seedPage * 3 + i < seeds.size(); i++) {
            ClientEntity seed = seeds.get(v.seedPage * 3 + i); Identifier plant = seed.defId();
            SeedCardRenderer.draw(c, SeedCardRenderer.CardModel.of(com.pvzce.client.gui.hud.cardbar.CardPainter.icon(plant.toString()), SeedCardRenderer.CardKind.PLANT, 0),
                    left + (772 + i * 57) * s, 47 * s, 49 * s, 65 * s);
        }
        if (seeds.isEmpty()) fitText(c, "合成后在这里取卡", 775, 76, 166, 0.58F, 0.70F, 0.64F);
        button(c, "‹", 772, 8, 25, 26, v.seedPage > 0);
        text(c, seeds.size() + " 张 · " + (v.seedPage + 1) + "/" + seedPages, 806, 18, 0.65F, 0.7F, 0.8F, 0.74F);
        button(c, "›", 917, 8, 25, 26, v.seedPage < seedPages - 1);
        double mx = (c.guiMouseX(c.window().cursorX()) - left) / s;
        double my = c.guiMouseY(c.window().cursorY()) / s;
        for (int i = 0; i < 3 && v.seedPage * 3 + i < seeds.size(); i++) {
            if (inside(mx, my, 772 + i * 57, 47, 49, 65)) {
                String label = com.pvzce.client.gui.HoverTip.nameOf(seeds.get(v.seedPage * 3 + i).defId().toString(), "plant");
                fitText(c, label, 772, 34, 171, 1F, 0.88F, 0.59F);
            }
        }
        // Visible on the lawn and also collectable together from the workshop; no expiry pressure.
        for (FusionState.Drop drop : state.drops()) {
            float x = c.camera().screenX(drop.x()) / Math.max(1, c.guiScale());
            float y = c.camera().screenY(drop.y() + 0.45F) / Math.max(1, c.guiScale());
            c.drawSolid(x - 15 * s, y - 9 * s, 30 * s, 18 * s, 8F, 0.24F, 0.63F, 0.60F, 0.92F);
            c.fonts().body().draw(name(drop.ability()), x - 12 * s, y - 3 * s, 0.6F * s, 1F, 1F, 0.80F, 1F);
        }
    }

    private static boolean inside(double x, double y, float rx, float ry, float w, float h) {
        return x >= rx && x < rx + w && y >= ry && y < ry + h;
    }
    public static boolean click(PvzceClient c, double guiX, double guiY, int button) {
        if (!active(c.level())) return false;
        View v = view(c.level()); if (v.state == null) return false;
        float s = scale(c); double x = (guiX - (c.guiWidth() - WIDTH * s) / 2F) / s, y = guiY / s;
        if (y >= HEIGHT) {
            if (button == 0) for (FusionState.Drop drop : v.state.drops()) {
                double dx = c.camera().screenX(drop.x()) / Math.max(1, c.guiScale()), dy = c.camera().screenY(drop.y() + 0.45F) / Math.max(1, c.guiScale());
                if (Math.hypot((guiX - dx) / s, (guiY - dy) / s) < 23) { send(c, "collect", "", drop.id()); return true; }
            }
            return false;
        }
        if (button != 0) return true;
        if (inside(x, y, 12, 28, 84, 62)) { toggleShovel(c); return true; }
        List<Identifier> ids = abilities(v);
        for (int i = 0; i < PAGE_SIZE && v.page * PAGE_SIZE + i < ids.size(); i++) {
            String id = ids.get(v.page * PAGE_SIZE + i).toString(); float rx = 120 + i % 4 * 84, ry = 86 - i / 4 * 27;
            if (inside(x, y, rx, ry, 61, 24)) send(c, "add", id, -1);
            if (inside(x, y, rx + 63, ry, 18, 24)) send(c, "buy", id, -1);
        }
        if (inside(x, y, 120, 8, 25, 19)) v.page = Math.max(0, v.page - 1);
        if (inside(x, y, 204, 8, 25, 19)) v.page = Math.min((ids.size() - 1) / PAGE_SIZE, v.page + 1);
        if (inside(x, y, 242, 8, 207, 19)) send(c, "collect", "", -1);
        for (int i = 0; i < 6 && v.trayPage * 6 + i < v.state.tray().size(); i++) {
            if (inside(x, y, 478 + i % 3 * 87, 82 - i / 3 * 26, 82, 23)) send(c, "remove", v.state.tray().get(v.trayPage * 6 + i).ability(), -1);
        }
        if (inside(x, y, 474, 8, 140, 34)) send(c, "fuse", "", -1);
        if (inside(x, y, 621, 8, 91, 34)) send(c, "clear", "", -1);
        if (inside(x, y, 718, 8, 25, 34)) v.trayPage = (v.trayPage + 1) % Math.max(1, (v.state.tray().size() + 5) / 6);
        List<ClientEntity> seeds = seeds(c);
        for (int i = 0; i < 3 && v.seedPage * 3 + i < seeds.size(); i++) {
            if (inside(x, y, 772 + i * 57, 47, 49, 65) && !c.level().holdingCard()) {
                v.shovel = false; c.connection().send(new PickUpCardC2S(seeds.get(v.seedPage * 3 + i).id()));
            }
        }
        if (inside(x, y, 772, 8, 25, 26)) v.seedPage = Math.max(0, v.seedPage - 1);
        if (inside(x, y, 917, 8, 25, 26)) v.seedPage = Math.min(Math.max(0, (seeds.size() - 1) / 3), v.seedPage + 1);
        return true;
    }
    public static void toggleShovel(PvzceClient c) {
        View v = view(c.level()); v.shovel = !v.shovel;
        if (v.shovel && c.level().holdingCard()) c.connection().send(new ReleaseHeldCardC2S());
    }
    public static void cancelShovel(ClientLevel level) { if (active(level)) view(level).shovel = false; }
    public static boolean dig(PvzceClient c, int x, int y) {
        if (!active(c.level()) || !view(c.level()).shovel) return false;
        c.connection().send(new UseGrantedToolC2S(PvzceIds.id("shovel"), x, y, c.level().activeSurface()));
        return true;
    }
}
