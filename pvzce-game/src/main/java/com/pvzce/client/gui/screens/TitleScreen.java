package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.Dialog;
import com.pvzce.client.gui.layout.MenuPageCanvas;
import com.pvzce.client.gui.layout.MenuPageCanvas.Canvas;
import com.pvzce.client.gui.mods.ModsScreen;
import com.pvzce.client.gui.workshop.WorkshopScreen;
import com.pvzce.common.util.WorldPaths;
import net.fabricmc.loader.api.FabricLoader;

import java.util.EnumMap;

/** Original selector scenery, with Chinese stone plaques for the community features. */
public final class TitleScreen extends Screen {
    private static final Identifier LOGO =
            Identifier.withDefaultNamespace("textures/gui/screen/title/title_logo");

    /** Coordinates measured from SelectorScreen.reanim; native Y grows down from the top. */
    enum Entry {
        START("start", 405, 65, 329, 143, true, true),
        WORKSHOP("workshop", 406, 173, 313, 133, true, true),
        MODS("mods", 410, 257, 286, 122, true, true),
        PACKS("packs", 413, 370, 266, 90, true, true),
        PLAYER("player", 23, 0, 293, 150, false, false),
        SWITCH("switch", 25, 139, 291, 71, false, false),
        SHOP("shop", 324, 481, 130, 89, false, false),
        // right.png probe: left vase face x=490..565/y=440..475, right vase
        // x=640..695/y=455..490; add the scenery's (71,41) native origin.
        SETTINGS("settings", 567, 486, 68, 26, true, false),
        QUIT("quit", 717, 498, 48, 26, true, false);

        final Identifier texture;
        final float x, top, width, height;
        final boolean right, stone;

        Entry(String texture, float x, float top, float width, float height,
              boolean right, boolean stone) {
            this.texture = art(texture);
            this.x = x;
            this.top = top;
            this.width = width;
            this.height = height;
            this.right = right;
            this.stone = stone;
        }
    }

    /** Both anchors use the existing native menu canvas mapping, with a shared scale. */
    record Layout(Canvas left, Canvas right) {
        Canvas canvas(Entry entry) {
            return entry.right ? right : left;
        }
    }

    static Layout layoutForGui(int width, int height) {
        float scale = Math.max(0.001F, Math.min(width / MenuPageCanvas.NATIVE_WIDTH,
                height / MenuPageCanvas.NATIVE_HEIGHT));
        float bottom = (height - MenuPageCanvas.NATIVE_HEIGHT * scale) / 2F;
        return new Layout(new Canvas(scale, 0F, bottom),
                new Canvas(scale, width - MenuPageCanvas.NATIVE_WIDTH * scale, bottom));
    }

    private final EnumMap<Entry, MenuButton> entries = new EnumMap<>(Entry.class);
    private Layout layout = layoutForGui(427, 240);
    private final long openedAt = System.nanoTime();

    public TitleScreen(PvzceClient client) {
        super(client);
        // Windowless clients can still exercise the real entry buttons and their dispatch.
        buildMenu();
    }

    private static Identifier art(String name) {
        return Identifier.withDefaultNamespace("textures/gui/screen/title/selector/" + name);
    }

    @Override
    protected void init() {
        if (client.music() != null) {
            client.music().ensureMenu("pvzce:music/crazy_dave");
        }
        layout = layoutForGui(client.guiWidth(), client.guiHeight());
        buildMenu();
    }

    private void buildMenu() {
        clearWidgets();
        entries.clear();
        addEntry(Entry.START, GuiLang.raw("gui.pvzce.title.start", "开始游戏"), this::enterCurrentPlayer);
        addEntry(Entry.WORKSHOP, GuiLang.raw("gui.pvzce.workshop.title", "创意工坊"),
                () -> client.openScreen(new WorkshopScreen(client)));
        addEntry(Entry.MODS, GuiLang.raw("gui.pvzce.title.mods", "模组"),
                () -> client.openScreen(new ModsScreen(client)));
        addEntry(Entry.PACKS, GuiLang.raw("gui.pvzce.packs.title", "数据包"),
                () -> client.openScreen(new PackScreen(client)));
        addEntry(Entry.PLAYER, "谁要玩游戏？", this::openPlayerPicker);
        addEntry(Entry.SWITCH, "切换玩家", this::openPlayerPicker);
        addEntry(Entry.SHOP, GuiLang.raw("gui.pvzce.shop.title", "商店"), () -> {
            client.requestProfile();
            client.openScreen(new ShopScreen(client));
        });
        addEntry(Entry.SETTINGS, GuiLang.raw("gui.pvzce.title.settings", "设置"),
                () -> client.openScreen(new SettingsScreen(client)));
        addEntry(Entry.QUIT, GuiLang.raw("gui.pvzce.title.quit", "退出"),
                () -> client.window().requestClose());
    }

    private void addEntry(Entry entry, String label, Runnable action) {
        MenuButton button = new MenuButton(layout.canvas(entry), entry, label, action);
        entries.put(entry, button);
        addWidget(button);
    }

    /** A sprite button remains a real Button, including sound, modal dispatch and smoke discovery. */
    private static final class MenuButton extends Button {
        private final Entry entry;

        MenuButton(Canvas canvas, Entry entry, String label, Runnable action) {
            super(Math.round(canvas.x(entry.x)), Math.round(canvas.y(entry.top + entry.height)),
                    Math.round(canvas.scaled(entry.width)), Math.round(canvas.scaled(entry.height)),
                    label, action);
            this.entry = entry;
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            if (!super.isMouseOver(mouseX, mouseY)) {
                return false;
            }
            if (!entry.stone) {
                return true;
            }
            // The plaques overlap as rectangles. Follow their sloping faces so transparent
            // corners never steal a click from the plaque visibly underneath them.
            return onStoneFace(entry, (float) ((mouseX - x) / width),
                    (float) (1D - (mouseY - y) / height));
        }

        @Override
        public void render(PvzceClient client) {
            if (!client.hasTexture(entry.texture)) {
                super.render(client);
                return;
            }
            float lift = hovered ? Math.max(0.5F, height / 55F) : 0F;
            client.drawTexture(entry.texture, x, y + lift, width, height, 0.1F,
                    1F, hovered ? 1F : 0.96F, hovered ? 0.78F : 1F, 1F);
        }
    }

    static boolean onStoneFace(Entry entry, float u, float v) {
        if (entry == Entry.PACKS) {
            return u >= 0.025F && u <= 0.975F && v >= 0.15F && v <= 0.85F;
        }
        return u >= 0.025F && u <= 0.975F
                && v >= 0.09F + 0.34F * u && v <= 0.64F + 0.34F * u;
    }

    /** Legacy smoke hook: shop and packs now point to their actual, separately placed buttons. */
    public record TrayCell(float centerX, float centerY) {
    }

    public TrayCell trayCell(int index) {
        Entry entry = switch (index) {
            case 0 -> Entry.SHOP;
            case 1 -> Entry.PACKS;
            default -> throw new IllegalArgumentException("Unknown menu shortcut: " + index);
        };
        Button button = entries.get(entry);
        return new TrayCell(button.x() + button.width() / 2F, button.y() + button.height() / 2F);
    }

    private void openPlayerPicker() {
        showDialog(PlayerPickerDialog.create(client, client::setCurrentWorld));
    }

    private void enterCurrentPlayer() {
        if (currentPlayer() == null) {
            openPlayerPicker();
        } else {
            client.openScreen(new LevelSelectScreen(client));
        }
    }

    private String currentPlayer() {
        String name = client.currentWorld();
        return name == null || name.isBlank()
                || !WorldPaths.worldDir(client.gameDir(), name).toFile().isDirectory() ? null : name;
    }

    @Override
    public void render() {
        client.beginGuiView();
        float seconds = (System.nanoTime() - openedAt) / 1_000_000_000F;
        renderScenery(seconds);
        // Logo belongs to the left canopy; the house and lawn remain visible beneath it.
        draw(layout.left(), LOGO, 29, 211, 300, 300F * 725F / 2170F);
        for (var widget : widgets) {
            if (!(widget instanceof Dialog)) {
                widget.render(client);
            }
        }
        renderPlayerName();
        renderBuildLine();
        renderAccountStatus();
        renderServerMessage();
        // Names and transient menu messages belong behind modal backdrops too.
        for (Dialog dialog : dialogs()) {
            dialog.render(client);
        }
    }

    private void renderScenery(float seconds) {
        if (!client.hasTexture(art("sky"))) {
            renderBackground(0.16F, 0.3F, 0.13F);
            return;
        }
        client.drawTexture(art("sky"), 0, 0, client.guiWidth(), client.guiHeight(), -2F,
                1F, 1F, 1F, 1F);
        Canvas left = layout.left();
        Canvas right = layout.right();
        float extra = (right.originX() - left.originX()) / left.scale();
        // Only the distant lawn extends horizontally. Foreground art and every button retain
        // their aspect; the tree stays left and the large tombstone stays right on wide screens.
        draw(left, art("cloud"), 230F + extra / 2F + 12F * (float) Math.sin(seconds * 0.12F),
                28, 434, 144);
        draw(left, art("center"), 80, 250, 720F + extra, 350);
        draw(left, art("left"), 0, -80, 476, 680);
        draw(right, art("right"), 71, 41, 730, 560);
        draw(left, art("leaves"), -2, 537F + (float) Math.sin(seconds * 1.1F), 240, 62);
        drawFlower(right, "flower1", 680, 414, 36, 47, seconds);
        drawFlower(right, "flower2", 640, 437, 43, 43, seconds + 1.4F);
        drawFlower(right, "flower3", 740, 458, 46, 53, seconds + 2.7F);
    }

    private void drawFlower(Canvas canvas, String name, float x, float y,
                            float width, float height, float phase) {
        float sway = 1.2F * (float) Math.sin(phase * 1.3F);
        draw(canvas, art(name), x + sway, y, width, height);
    }

    private void draw(Canvas canvas, Identifier texture, float x, float top, float width, float height) {
        if (client.hasTexture(texture)) {
            client.drawTexture(texture, canvas.x(x), canvas.y(top + height),
                    canvas.scaled(width), canvas.scaled(height), 0F, 1F, 1F, 1F, 1F);
        }
    }

    private void renderPlayerName() {
        Button board = entries.get(Entry.PLAYER);
        String current = currentPlayer();
        String name = current == null ? "请选择玩家" : current;
        float size = layout.left().scaled(1.55F);
        float available = board.width() * 0.8F;
        size *= Math.min(1F, available / Math.max(1F, client.fonts().button().width(name, size)));
        float textWidth = client.fonts().button().width(name, size);
        client.fonts().button().draw(name, board.x() + (board.width() - textWidth) / 2F,
                layout.left().y(118), size, 1F, 0.94F, 0.68F, 1F);
    }

    private void renderAccountStatus() {
        var account = client.account().view();
        String text = account.message() + (account.displayName().isEmpty() ? "" : "：" + account.displayName());
        float scale = Math.min(0.8F, layout.left().scaled(1F));
        scale = Math.min(scale, (client.guiWidth() - 24F) / Math.max(1F, client.fonts().body().width(text, 1F)));
        float y = client.fonts().body().lineHeight(scale) + 18;
        client.drawSolid(8, y - 4, client.fonts().body().width(text, scale) + 8,
                client.fonts().body().lineHeight(scale) + 6, 0.2F, .02F, .06F, .02F, .75F);
        client.fonts().body().draw(text, 12, y, scale, .94F, .9F, .72F, 1F);
    }

    private void renderBuildLine() {
        String loader = FabricLoader.getInstance().getModContainer("fabricloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String info = "PVZCE 1.0.0 · fabricloader " + loader + " · "
                + FabricLoader.getInstance().getAllMods().size() + " mods · "
                + System.getProperty("pvzce.versionType", "release");
        float scale = Math.min(0.7F, layout.left().scaled(0.9F));
        float width = client.fonts().body().width(info, scale);
        client.drawSolid(client.guiWidth() - width - 12, 3, width + 9,
                client.fonts().body().lineHeight(scale) + 5, 0.2F, 0.02F, 0.06F, 0.02F, 0.65F);
        client.fonts().body().draw(info, client.guiWidth() - width - 8, 7, scale,
                0.82F, 0.85F, 0.72F, 1F);
    }

    private void renderServerMessage() {
        if (!dialogs().isEmpty()) {
            return;
        }
        String message = client.recentServerMessage();
        if (message.isEmpty()) {
            return;
        }
        float scale = Math.min(1.15F, layout.left().scaled(1.4F));
        float width = client.fonts().button().width(message, scale);
        float x = (client.guiWidth() - width) / 2F;
        float y = client.guiHeight() / 2F + 10F;
        client.drawSolid(x - 8, y - 6, width + 16,
                client.fonts().button().lineHeight(scale) + 12, 0.3F, 0.05F, 0.06F, 0.03F, 0.9F);
        client.fonts().button().draw(message, x, y, scale, 1F, 0.65F, 0.4F, 1F);
    }
}
