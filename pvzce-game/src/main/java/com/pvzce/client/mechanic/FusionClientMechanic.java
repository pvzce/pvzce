package com.pvzce.client.mechanic;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.ClientLevel;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.HoverTip;
import com.pvzce.client.gui.SeedCardRenderer;
import com.pvzce.client.gui.components.NinePatch;
import com.pvzce.client.gui.hud.cardbar.CardPainter;
import com.pvzce.client.gui.hud.cardbar.CardBar;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.PlantRecipes;
import com.pvzce.common.level.FusionState;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.packet.FusionActionC2S;
import com.pvzce.common.network.packet.PickUpCardC2S;
import com.pvzce.common.network.packet.SlotInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.pvzce.client.mechanic.FusionLayout.*;

/** Familiar wood, paper and tool slots surrounding the lawn; all inventory remains a server mirror. */
public final class FusionClientMechanic implements ClientMechanic {
    private static final Identifier WOOD = PvzceIds.id("textures/gui/screen/seeds/seed_chooser_background");
    private static final Identifier BUTTON = PvzceIds.id("textures/gui/screen/seeds/seed_chooser_button");
    private static final Identifier BUTTON_DISABLED = PvzceIds.id("textures/gui/screen/seeds/seed_chooser_button_disabled");
    private static final Identifier PAPER = PvzceIds.id("textures/gui/hud/seed_packet");
    private static final Identifier SUN_BANK = PvzceIds.id("textures/gui/hud/sun_bank");
    private static final class View {
        FusionState state;
        int page, seedPage, trayPage;
        String notice = "";
        long noticeUntil;
        final Map<Identifier, Identifier> icons = new HashMap<>();
    }
    private static View view(ClientLevel level) {
        return level.mechanicState(PvzceIds.MECHANIC_FUSION, View::new);
    }
    public static boolean active(ClientLevel level) { return level.hasMechanic(PvzceIds.MECHANIC_FUSION); }
    private static FusionLayout layout(PvzceClient c) {
        float right = c.currentScreen() instanceof com.pvzce.client.gui.screens.InGameScreen screen
                ? screen.rightBound() : c.guiWidth();
        return FusionLayout.of(c.guiWidth(), c.guiHeight(), right);
    }
    public static float[] sunTarget(PvzceClient c) {
        var l = layout(c);
        return new float[]{l.left() + 46 * l.scale(), (l.topY() + 15) * l.scale()};
    }
    @Override public Identifier id() { return PvzceIds.MECHANIC_FUSION; }
    @Override public CardBar createCardBar(CardBar.Host host) {
        // The screen owns selection, cursor, dragging, cancellation and one-use behaviour,
        // exactly as for a normal shovel. Only this bar's drawing and picking position differ.
        return new CardBar() {
            @Override public List<SlotInfo> slots() { return host.client().level().slots(); }
            @Override public void tick() { }
            @Override public void render() { }
            @Override public int slotAt(double x, double y) {
                if (!contains(x, y)) return -1;
                return toolSlots().stream().filter(s -> "pvzce:shovel".equals(s.defId()))
                        .mapToInt(SlotInfo::index).findFirst().orElse(-1);
            }
            @Override public boolean contains(double x, double y) {
                var l = layout(host.client()); return l.shovel().contains(l.x(x), l.y(y));
            }
            @Override public boolean scroll(double x, double y, double amount) { return false; }
            @Override public int cardHeight() { return Math.round(layout(host.client()).shovel().height() * layout(host.client()).scale()); }
        };
    }
    @Override public void applySync(ClientLevel level, PacketByteBuf payload) {
        View v = view(level); v.state = FusionState.CODEC.decode(payload);
        v.noticeUntil = 0L;
    }
    public static void showNotice(ClientLevel level, String message) {
        if (!active(level)) return;
        View v = view(level); v.notice = message; v.noticeUntil = System.nanoTime() + 4_000_000_000L;
    }
    private static List<Identifier> abilities() {
        List<Identifier> result = new ArrayList<>(PlantRecipes.abilities());
        List<String> first = List.of("shooter", "producer", "defense", "carrier", "explosive", "thrower", "nocturnal", "melee");
        result.sort(Comparator.<Identifier>comparingInt(id -> first.indexOf(id.path()) < 0 ? first.size() : first.indexOf(id.path()))
                .thenComparing(Identifier::toString));
        return result;
    }
    private static int count(List<FusionState.Count> counts, String ability) {
        return counts.stream().filter(c -> c.ability().equals(ability)).mapToInt(FusionState.Count::amount).sum();
    }
    private static List<ClientEntity> seeds(PvzceClient c) {
        return c.level().entities().values().stream().filter(e -> "card_drop".equals(e.kind())
                && e.id() != c.level().heldCardEntityId()).sorted(Comparator.comparingInt(ClientEntity::id)).toList();
    }
    private static String name(String ability) {
        Identifier id = Identifier.tryParse(ability);
        if (id == null) return ability;
        return GuiLang.raw("plant_capability." + id.namespace() + "." + id.path(),
                GuiLang.raw("capability." + id.namespace() + "." + id.path(), ability));
    }
    private static Identifier abilityIcon(View v, Identifier ability) {
        return v.icons.computeIfAbsent(ability, key -> BuiltInRegistries.PLANTS.keySet().stream()
                .map(BuiltInRegistries.PLANTS::get)
                .filter(plant -> plant.resolvedCapabilities().stream().anyMatch(cap -> cap.type().equals(key)))
                .min(Comparator.comparingInt(PlantDef::order).thenComparing(plant -> plant.id().toString()))
                .map(plant -> CardPainter.icon(plant.id().toString())).orElse(null));
    }
    private static void send(PvzceClient c, String action, String ability, int drop) {
        c.connection().send(new FusionActionC2S(action, ability, drop));
    }
    private static boolean hovered(PvzceClient c, Box box) {
        var l = layout(c);
        return box.contains(l.x(c.guiMouseX(c.window().cursorX())), l.y(c.guiMouseY(c.window().cursorY())));
    }
    private static void texture(PvzceClient c, Identifier id, Box box, float brightness) {
        var l = layout(c);
        c.drawTexture(id, l.left() + box.x() * l.scale(), box.y() * l.scale(),
                box.width() * l.scale(), box.height() * l.scale(), 8F, brightness, brightness, brightness, 1F);
    }
    private static void wash(PvzceClient c, Box box, float alpha) {
        var l = layout(c);
        c.drawSolid(l.left() + box.x() * l.scale(), box.y() * l.scale(), box.width() * l.scale(), box.height() * l.scale(),
                8.1F, 1F, 0.95F, 0.45F, alpha);
    }
    private static void wood(PvzceClient c, Box box) {
        var l = layout(c);
        // The native frame has transparent carved corners. Give them a wooden
        // backing so edge-to-edge toolbars never expose the clear colour.
        c.drawSolid(l.left() + box.x() * l.scale(), box.y() * l.scale(),
                box.width() * l.scale(), box.height() * l.scale(), 7.9F, 0.22F, 0.075F, 0.024F, 1F);
        NinePatch.drawNineSliceTiled(c, WOOD, l.left() + box.x() * l.scale(), box.y() * l.scale(),
                box.width() * l.scale(), box.height() * l.scale(), 8F,
                465F, 513F, 16F, 16F, 34F, 16F, 0.45F * l.scale(), 1F, 1F, 1F, 1F);
    }
    private static void text(PvzceClient c, String value, float x, float y, float size, float r, float g, float b) {
        var l = layout(c);
        c.fonts().body().draw(value, l.left() + x * l.scale(), y * l.scale(), size * l.scale(), r, g, b, 1F);
    }
    private static void fitText(PvzceClient c, String value, float x, float y, float width, float size, float r, float g, float b) {
        text(c, value, x, y, Math.min(size, width / Math.max(1F, c.fonts().body().width(value, 1F))), r, g, b);
    }
    private static void button(PvzceClient c, String label, Box box, boolean enabled) {
        texture(c, enabled ? BUTTON : BUTTON_DISABLED, box, 1F);
        if (enabled && hovered(c, box)) wash(c, box, 0.14F);
        float size = Math.min(0.8F, Math.min((box.width() - 8F) / Math.max(1F, c.fonts().button().width(label, 1F)),
                box.height() * 0.55F / c.fonts().button().lineHeight(1F)));
        var l = layout(c);
        c.fonts().button().draw(label,
                l.left() + (box.x() + (box.width() - c.fonts().button().width(label, size)) / 2F) * l.scale(),
                (box.y() + (box.height() - c.fonts().button().lineHeight(size)) / 2F + 2F) * l.scale(),
                size * l.scale(), 1F, 0.97F, 0.82F, enabled ? 1F : 0.55F);
    }
    private static void token(PvzceClient c, View v, String ability, int amount, Box box) {
        var l = layout(c); Identifier icon = abilityIcon(v, Identifier.tryParse(ability));
        float bright = amount > 0 ? 1F : 0.52F;
        if (icon == null) {
            texture(c, PAPER, box, bright);
            text(c, "?", box.x() + box.width() / 2F - 5F, box.y() + box.height() / 2F, 1F, 0.35F, 0.22F, 0.10F);
        } else {
            SeedCardRenderer.draw(c, SeedCardRenderer.CardModel.of(icon, SeedCardRenderer.CardKind.BUFF, SlotInfo.NO_PRICE).withBrightness(bright),
                    l.left() + box.x() * l.scale(), box.y() * l.scale(), box.width() * l.scale(), box.height() * l.scale());
        }
        fitText(c, name(ability), box.x() + 3F, box.y() + 3F, box.width() - 6F, 0.58F, 0.18F, 0.27F, 0.09F);
        if (box.width() >= 32F) {
            // A separate wooden quantity tag stays readable over the packet's printed header.
            button(c, "×" + amount, new Box(box.x() + box.width() - 26F, box.y() + box.height() - 16F, 24F, 14F), amount > 0);
        }
        if (amount > 0 && hovered(c, box)) wash(c, box, 0.16F);
    }
    private static void seed(PvzceClient c, Identifier plant, Box box) {
        var l = layout(c);
        SeedCardRenderer.draw(c, SeedCardRenderer.CardModel.of(CardPainter.icon(plant.toString()), SeedCardRenderer.CardKind.PLANT, SlotInfo.NO_PRICE),
                l.left() + box.x() * l.scale(), box.y() * l.scale(), box.width() * l.scale(), box.height() * l.scale());
        if (hovered(c, box)) wash(c, box, 0.12F);
    }

    @Override public void renderHud(PvzceClient c) {
        View v = view(c.level()); FusionState state = v.state;
        if (state == null) return;
        var l = layout(c);
        var data = c.level().mechanicData(id(), com.pvzce.api.content.FusionData.class);
        int loss = Math.round(100F * data.lossChance());
        // The materials reach the actual window edges, including wide windows
        // whose centered controls leave space outside the reference layout.
        float edge = -l.left() / l.scale(), fullWidth = c.guiWidth() / l.scale();
        wood(c, new Box(edge, l.topY(), fullWidth, TOP_HEIGHT));
        wood(c, new Box(WORKSHOP_LEFT, 0, fullWidth + edge - WORKSHOP_LEFT, l.topY()));
        wood(c, new Box(edge, 0, WORKSHOP_LEFT - edge, FOOTER_HEIGHT));
        texture(c, SUN_BANK, new Box(14, l.topY() + 5, 64, 62), 1F);
        String sun = String.valueOf(c.level().sun());
        float sunSize = Math.min(0.9F, 49F / Math.max(1F, c.fonts().button().width(sun, 1F)));
        c.fonts().button().draw(sun, l.left() + (46F - c.fonts().button().width(sun, sunSize) / 2F) * l.scale(),
                (l.topY() + 10F) * l.scale(), sunSize * l.scale(), 0.18F, 0.10F, 0.03F, 1F);
        for (SlotInfo slot : c.level().slots()) if ("pvzce:shovel".equals(slot.defId())) {
            Box box = l.shovel();
            boolean selected = c.currentScreen() instanceof com.pvzce.client.gui.screens.InGameScreen screen
                    && screen.selectedCardIndex() == slot.index();
            CardPainter.draw(c, slot, l.left() + box.x() * l.scale(), box.y() * l.scale(),
                    box.width() * l.scale(), box.height() * l.scale(), 1F, selected);
        }
        int pageSize = l.abilitiesPerPage();
        List<Identifier> ids = abilities(); int pages = Math.max(1, (ids.size() + pageSize - 1) / pageSize);
        v.page = Math.min(v.page, pages - 1);
        for (int i = 0; i < l.abilitiesPerPage() && v.page * l.abilitiesPerPage() + i < ids.size(); i++) {
            String ability = ids.get(v.page * l.abilitiesPerPage() + i).toString();
            token(c, v, ability, count(state.inventory(), ability), l.ability(i));
            button(c, "+" + state.price(), l.buy(i), c.level().sun() >= state.price());
        }
        button(c, "‹", l.abilityPrevious(), v.page > 0);
        button(c, "›", l.abilityNext(), v.page < pages - 1);
        text(c, (v.page + 1) + " / " + pages, l.topRight() - 69F, l.topY() + 8, 0.65F, 1F, 0.9F, 0.66F);
        String[] lesson = {"选铲子，再点一株豌豆射手（首次无损）", "点击地面能力收集，再点上方的射手能力加料",
                "点“合成植物”，相同能力的背包植物随机出现一种", "取出右侧种子卡，再点可种植格子；种下后开始迎战"};
        if (state.tutorialStep() == 1 && state.inventory().isEmpty() && state.drops().isEmpty()) lesson[1] = "能力已经损耗：再分解一株豌豆射手，然后重新合成";
        String line = !state.success() ? state.message() : state.tutorialStep() < 4 ? lesson[state.tutorialStep()]
                : state.message().isEmpty() ? "点击能力加料、点击合成区取回；不用的卡片可分解，损耗 " + loss + "%" : state.message();
        if (System.nanoTime() < v.noticeUntil) line = v.notice;
        fitText(c, line, 15, 4, 710, 0.72F, 1F, state.success() ? 0.94F : 0.60F, 0.67F);
        fitText(c, "波次 " + c.level().currentWave() + "/" + c.level().totalWaves(), 752, 453, 150, 0.65F, 1F, 0.94F, 0.67F);
        text(c, "合成区", 752, 432, 0.72F, 1F, 0.95F, 0.75F);
        text(c, "点能力取回", 820, 433, 0.54F, 0.90F, 0.79F, 0.58F);
        int trayPages = Math.max(1, (state.tray().size() + TRAY_PER_PAGE - 1) / TRAY_PER_PAGE);
        v.trayPage = Math.min(v.trayPage, trayPages - 1);
        if (state.tray().isEmpty()) fitText(c, "从上方选能力加料", 759, 361, 178, 0.8F, 0.87F, 0.77F, 0.55F);
        for (int i = 0; i < TRAY_PER_PAGE && v.trayPage * TRAY_PER_PAGE + i < state.tray().size(); i++) {
            var entry = state.tray().get(v.trayPage * TRAY_PER_PAGE + i);
            token(c, v, entry.ability(), entry.amount(), l.tray(i));
        }
        button(c, "合成植物", l.fuse(), !state.tray().isEmpty());
        button(c, "全部返还", l.clear(), !state.tray().isEmpty());
        button(c, "›", l.trayNext(), trayPages > 1);
        button(c, "收集 (" + state.drops().size() + ")", l.collect(), !state.drops().isEmpty());
        text(c, "待种卡片", 796, 220, 0.65F, 1F, 0.95F, 0.75F);
        List<ClientEntity> seeds = seeds(c); int seedPages = Math.max(1, (seeds.size() + SEEDS_PER_PAGE - 1) / SEEDS_PER_PAGE);
        v.seedPage = Math.min(v.seedPage, seedPages - 1);
        for (int i = 0; i < SEEDS_PER_PAGE && v.seedPage * SEEDS_PER_PAGE + i < seeds.size(); i++) {
            var packet = seeds.get(v.seedPage * SEEDS_PER_PAGE + i);
            seed(c, packet.defId(), l.seed(i));
            button(c, "分解", l.recycle(i), true);
            if (hovered(c, l.seed(i)) || hovered(c, l.recycle(i))) {
                fitText(c, HoverTip.nameOf(packet.defId().toString(), "plant") + (hovered(c, l.recycle(i)) ? " · 损耗 " + loss + "%" : ""),
                        752, 96, 191, 0.62F, 1F, 0.94F, 0.66F);
            }
        }
        if (seeds.isEmpty()) text(c, "合成后在这里取卡", 757, 167, 0.65F, 0.87F, 0.77F, 0.55F);
        button(c, "‹", l.seedPrevious(), v.seedPage > 0);
        button(c, "›", l.seedNext(), v.seedPage < seedPages - 1);
        text(c, (v.seedPage + 1) + "/" + seedPages, 841, 204, 0.55F, 1F, 0.9F, 0.66F);
        text(c, "手持", 815, 78, 0.7F, 1F, 0.95F, 0.75F);
        if (c.level().holdingCard()) seed(c, Identifier.tryParse(c.level().heldCard()), l.held());
        else text(c, "为空", 760, 60, 0.7F, 0.87F, 0.77F, 0.55F);
        button(c, "分解手持", l.recycleHeld(), c.level().holdingCard());
        for (FusionState.Drop drop : state.drops()) {
            float x = (c.camera().screenX(drop.x()) / Math.max(1, c.guiScale()) - l.left()) / l.scale();
            float y = c.camera().screenY(drop.y() + 0.45F) / Math.max(1, c.guiScale()) / l.scale();
            token(c, v, drop.ability(), 1, new Box(x - 14, y - 20, 28, 40));
        }
    }

    public static boolean click(PvzceClient c, double guiX, double guiY, int button) {
        if (!active(c.level())) return false;
        View v = view(c.level()); if (v.state == null) return false;
        var l = layout(c); double x = l.x(guiX), y = l.y(guiY);
        if (!l.inToolbar(x, y)) {
            if (button == 0) for (FusionState.Drop drop : v.state.drops()) {
                double dx = c.camera().screenX(drop.x()) / Math.max(1, c.guiScale()), dy = c.camera().screenY(drop.y() + 0.45F) / Math.max(1, c.guiScale());
                if (Math.hypot((guiX - dx) / l.scale(), (guiY - dy) / l.scale()) < 24) {
                    send(c, "collect", "", drop.id()); return true;
                }
            }
            return false;
        }
        if (l.shovel().contains(x, y)) return false;
        if (button != 0) return true;
        List<Identifier> ids = abilities();
        for (int i = 0; i < l.abilitiesPerPage() && v.page * l.abilitiesPerPage() + i < ids.size(); i++) {
            String id = ids.get(v.page * l.abilitiesPerPage() + i).toString();
            if (l.ability(i).contains(x, y)) send(c, "add", id, -1);
            if (l.buy(i).contains(x, y)) send(c, "buy", id, -1);
        }
        if (l.abilityPrevious().contains(x, y)) v.page = Math.max(0, v.page - 1);
        if (l.abilityNext().contains(x, y)) v.page = Math.min((ids.size() - 1) / l.abilitiesPerPage(), v.page + 1);
        for (int i = 0; i < TRAY_PER_PAGE && v.trayPage * TRAY_PER_PAGE + i < v.state.tray().size(); i++) {
            if (l.tray(i).contains(x, y)) send(c, "remove", v.state.tray().get(v.trayPage * TRAY_PER_PAGE + i).ability(), -1);
        }
        if (l.fuse().contains(x, y)) send(c, "fuse", "", -1);
        if (l.clear().contains(x, y)) send(c, "clear", "", -1);
        if (l.trayNext().contains(x, y)) v.trayPage = (v.trayPage + 1) % Math.max(1, (v.state.tray().size() + TRAY_PER_PAGE - 1) / TRAY_PER_PAGE);
        if (l.collect().contains(x, y)) send(c, "collect", "", -1);
        List<ClientEntity> seeds = seeds(c);
        for (int i = 0; i < SEEDS_PER_PAGE && v.seedPage * SEEDS_PER_PAGE + i < seeds.size(); i++) {
            int id = seeds.get(v.seedPage * SEEDS_PER_PAGE + i).id();
            if (l.recycle(i).contains(x, y)) send(c, "recycle", "", id);
            if (l.seed(i).contains(x, y)) {
                if (!recycleSelected(c, id) && !c.level().holdingCard()) {
                    c.connection().send(new PickUpCardC2S(id));
                }
            }
        }
        if (l.seedPrevious().contains(x, y)) v.seedPage = Math.max(0, v.seedPage - 1);
        if (l.seedNext().contains(x, y)) v.seedPage = Math.min(Math.max(0, (seeds.size() - 1) / SEEDS_PER_PAGE), v.seedPage + 1);
        if (l.recycleHeld().contains(x, y) && c.level().holdingCard()) send(c, "recycle", "", c.level().heldCardEntityId());
        return true;
    }
    public static boolean recycleSelected(PvzceClient c, int entityId) {
        if (!active(c.level()) || !(c.currentScreen() instanceof com.pvzce.client.gui.screens.InGameScreen screen)) return false;
        if (c.level().slots().stream().noneMatch(s -> s.index() == screen.selectedCardIndex() && "pvzce:shovel".equals(s.defId()))) return false;
        send(c, "recycle", "", entityId); return true;
    }
}
