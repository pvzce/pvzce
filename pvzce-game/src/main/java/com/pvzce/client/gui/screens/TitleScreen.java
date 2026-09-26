package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.util.WorldPaths;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.HoverTip;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.NinePatch;
import com.pvzce.client.gui.layout.GuiLayout;
import com.pvzce.client.gui.mods.ModsScreen;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Main menu: who is playing, and the things the player can do from here.
 *
 * <p>Who is playing <em>is</em> the world list. A world directory is a player's save
 * ({@code saves/<name>/}), so the original's "who are you?" screen and this project's world
 * picker were always the same question asked in two places - which is why the answer used to
 * take two screens to give: 开始游戏 opened a world list, and the world list opened the levels.
 * The list lives on the title screen now, under its own prompt, and a click on a name is the
 * whole selection: that player's levels open directly.
 *
 * <p>The name is both the save directory and what dialogue calls the player
 * ({@code ${user_name}}), so it is worth showing after it has been picked - the header says who
 * the game currently thinks is playing.
 */
public final class TitleScreen extends Screen {
    /** Place your image here: assets/pvzce/textures/gui/screen/title/title_logo.png */
    private static final Identifier TITLE_LOGO =
            Identifier.withDefaultNamespace("textures/gui/screen/title/title_logo");
    /** Place your image here: assets/pvzce/textures/gui/screen/title/title_background.png */
    private static final Identifier TITLE_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/screen/title/title_background");
    private static final float TITLE_LOGO_ASPECT = 2170F / 725F;

    /**
     * The corner tray: two icon cells in a wooden plate, bottom left.
     *
     * <p>It is icon-only, and that is a consequence of the measurement rather than a style: at the
     * default window the GUI is 427x240, the menu column starts at x 74 and the player board above
     * it reaches x 146 - which leaves the bottom-left corner about 66 units wide. A cell that fits
     * an icon <em>and</em> a two-hanzi label needs about 100. The names live in the hover tip,
     * through {@link HoverTip}, which is the same channel the card bar uses to name a card.
     *
     * <p>Sizes are therefore all small, and <b>tied to the menu buttons</b> rather than fixed: the
     * plate stands one menu button tall, and its cells are that much again (see
     * {@link #layOutTray}). A fixed size was the first version, and it is what made the tray look
     * like a doodle in the corner at 1080p/2x - where the buttons are 68 GUI units tall and a
     * 22-unit icon beside them is a third of their height. Scaling with the buttons is also what
     * keeps the corner's share of the screen the same at every window size: measured against the
     * menu column's left edge, which is the boundary the tray must not cross.
     *
     * <p>The plate is {@code seed_chooser_background}, the game's own shallow box; the cells are
     * the level list's wooden plate, stretched.
     */
    private static final Identifier TRAY_PANEL =
            Identifier.withDefaultNamespace("textures/gui/screen/seeds/seed_chooser_background");
    private static final float TRAY_TEXTURE_WIDTH = 465F;
    private static final float TRAY_TEXTURE_HEIGHT = 513F;
    /** What the two cells carry: the game's own coin and seed packet. */
    private static final Identifier SHOP_ICON =
            Identifier.withDefaultNamespace("textures/resource/coin_gold");
    private static final Identifier PACKS_ICON =
            Identifier.withDefaultNamespace("textures/gui/hud/seed_packet");
    private static final Identifier CELL_PLATE =
            Identifier.withDefaultNamespace("textures/gui/screen/seeds/seed_chooser_button");
    /** How much wider than tall a cell is; the icon on it stays square. */
    private static final float CELL_ASPECT = 1.2F;
    /** The frame's share of the tray's height, per side: the art's own 32/513. */
    private static final float TRAY_FRAME_OF_HEIGHT = 32F / 513F;
    private static final float TRAY_SIDE_PAD = 10F;
    private static final float TRAY_TOP_PAD = 8F;
    private static final float TRAY_BOTTOM_PAD = 8F;
    private static final float CELL_GAP = 12F;
    /** The cell plate's height as a fraction of the cell, at the button art's own 156:42. */
    private static final float CELL_PLATE_OF_CELL = 0.34F;
    /** How many menu buttons tall a cell is; see {@link TrayLayout}. */
    private static final float CELL_OF_BUTTON = 1.5F;
    /**
     * The menu button height assumed before a window exists.
     *
     * <p>{@link #tray} is initialized with it so the tray has a sane shape in a windowless client
     * - it is what the tray's test drives, and a test must not be able to read a zeroed rectangle.
     * 27 is what the default window (GUI 427x240) computes, so the value is real rather than a
     * placeholder.
     */
    private static final float DEFAULT_BUTTON_HEIGHT = 27F;
    /** The default window's GUI width; the other half of the same placeholder. */
    private static final float DEFAULT_GUI_WIDTH = 427F;

    /**
     * The tray's whole geometry for one menu-button height.
     *
     * <p>A pure function rather than a handful of assignments in {@code init()}, because the menu's
     * button height only exists once a window does - and the rule "the corner scales with the menu"
     * is worth testing at sizes no headless client can lay out. {@code init()} calls it with the
     * real height; the test calls it with three.
     */
    record TrayLayout(float x, float y, float width, float height,
                      float cellWidth, float cellHeight, float plateHeight, float iconBox) {
        /**
         * The tray for a menu-button height and the room the corner actually has.
         *
         * <p>Two constraints, and the smaller wins. Vertically the cell is one and a half buttons
         * tall, so the icon beside a button-height column is still the biggest thing in the corner.
         * Horizontally it is what is left between the window's edge and the menu column, because
         * the column is centred and 280 units wide - and at the default window that leaves 65
         * units, where the vertical rule alone would draw a plate under the buttons.
         *
             * <p>The plate under the icon is sized from the icon, not from the cell: a short cell whose
         * plate took a third of its height left the icon a sliver (8 units at GUI 427x240), which
         * is the same "too small" the fixed-size version was replaced for.
         */
        static TrayLayout forCorner(float buttonHeight, float availableWidth) {
            float cellWidthByHeight = buttonHeight * CELL_OF_BUTTON * CELL_ASPECT;
            float cellWidthBySpace = (availableWidth - TRAY_SIDE_PAD * 2F - CELL_GAP) / 2F;
            float width = Math.max(16F, Math.round(Math.min(cellWidthByHeight, cellWidthBySpace)));
            float frame = buttonHeight * TRAY_FRAME_OF_HEIGHT;
            // The taller of the two rules, and the width's own aspect is one of them: a cell that
            // is 24 wide but 40 tall is mostly the plate, which leaves the icon a stamp.
            float height = Math.max(Math.round(buttonHeight * CELL_OF_BUTTON + frame * 2F
                    + TRAY_TOP_PAD + TRAY_BOTTOM_PAD),
                    Math.round(width / CELL_ASPECT + frame * 2F + TRAY_TOP_PAD + TRAY_BOTTOM_PAD));
            float cell = height - frame * 2F - TRAY_TOP_PAD - TRAY_BOTTOM_PAD;
            // The plate is as wide as the icon (the button art's own proportion), capped so the
            // icon above it never grows past the menu button beside it - a cell whose width and
            // height disagree (a short, wide window) is what made that happen.
            float plate = Math.min(
                    Math.min(Math.max(6F, width * CELL_PLATE_OF_CELL),
                            Math.max(6F, cell - buttonHeight)),
                    Math.max(6F, cell - 10F));
            // The icon is square and inside the cell both ways: `cell - plate` alone ignores the
            // cell's width, and at the default window (a 16-unit-wide cell) that reported a 34-unit
            // icon - which the renderer then had to squeeze back down to 16.
            float icon = Math.max(6F, Math.min(width, cell - plate));
            return new TrayLayout(8F, 8F, width * 2F + CELL_GAP + TRAY_SIDE_PAD * 2F, height,
                    width, cell, plate, icon);
        }

        float cellX(int index) {
            return x + TRAY_SIDE_PAD + index * (cellWidth + CELL_GAP);
        }

        float cellY() {
            return y + TRAY_BOTTOM_PAD;
        }
    }

    /**
     * The tray's geometry for the current window, laid out in {@link #init()}.
     *
     * <p>One record rather than six float fields, because "where is the cell" is asked three times
     * (drawing, hit-testing and the test) and three readers of six fields is three chances to read
     * a stale one - which is exactly what happened: {@code trayCell()} was still reading zeros
     * after the layout moved into a local.
     */
    private TrayLayout tray =
            TrayLayout.forCorner(DEFAULT_BUTTON_HEIGHT, DEFAULT_GUI_WIDTH);

    private int titleY;
    private int subtitleY;
    private float titleScale;
    private float subtitleScale;
    private int buttonTop;
    /** Where the player name is drawn; also the click target that opens the picker. */
    private int nameX;
    private int nameY;
    private int nameWidth;
    private int nameHeight;

    public TitleScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected Identifier backgroundTexture() {
        return TITLE_BACKGROUND;
    }

    @Override
    protected void init() {
        if (client.music() != null) {
            // Null in a headless test client, which has no audio device to open. The same guard
            // the in-game screens carry, for the same reason: a menu that cannot play music is
            // still a menu.
            client.music().ensureMenu("pvzce:music/crazy_dave");
        }
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        int buttonWidth = Math.min(280, guiW - 24);
        int titleReserve = Math.max(56, Math.min(140, guiH * 30 / 100));

        // 48px legacy height enlarged by 40%; shrinks automatically when 4x UI has less room.
        // Four entries: the shop and the packs page used to sit here as the second and third of
        // six buttons, and stopped being the right shape for that the moment they became pages of
        // their own - a menu row is a list of things you *do*, and these two are a corner of the
        // game you *visit*. They live in the tray at the bottom left now (see {@link #renderTray}),
        // which also gave the menu column the room to breathe.
        int buttonHeight = GuiLayout.fitHeight(guiH, 68, 4, titleReserve, 8);
        int gap = GuiLayout.gapFor(buttonHeight);
        int blockHeight = 4 * buttonHeight + 3 * gap;

        // Buttons are anchored to the bottom so the menu fills the screen and
        // no large blank strip remains under the last button.
        int blockTop = 8 + blockHeight;
        buttonTop = blockTop;
        // The labels are language keys now rather than literals: this array is where the menu's
        // text lives, and it was the last place in this file where a translation could not
        // reach. The shop's and the packs page's keys are theirs - the tray cells and the pages
        // say the same word on purpose.
        String[] labels = {
                GuiLang.raw("gui.pvzce.title.start", "Start playing"),
                GuiLang.raw("gui.pvzce.title.mods", "Mods"),
                GuiLang.raw("gui.pvzce.title.settings", "Settings"),
                GuiLang.raw("gui.pvzce.title.quit", "Quit")
        };
        Runnable[] actions = {
                this::enterCurrentPlayer,
                () -> client.openScreen(new ModsScreen(client)),
                () -> client.openScreen(new SettingsScreen(client)),
                () -> client.window().requestClose()
        };
        int x = centerX(buttonWidth);
        for (int i = 0; i < labels.length; i++) {
            int y = blockTop - buttonHeight - i * (buttonHeight + gap);
            addWidget(new Button(x, y, buttonWidth, buttonHeight, labels[i], actions[i]));
        }

        // Title and subtitle sit in the space left above the button block,
        // never above the window edge.
        subtitleScale = Math.min(1.2F, Math.max(0.8F, guiH / 240F));
        subtitleY = blockTop + gap;
        titleY = subtitleY + client.fonts().body().lineHeight(subtitleScale) + gap;
        float availableAbove = Math.max(20F, guiH - titleY - 4F);
        titleScale = Math.min(4.2F, Math.max(0.9F, availableAbove / 30F));

        // Who is playing: a prompt with the name under it, in the top-left corner, and the whole
        // board is the button that opens the picker. One widget rather than two - the label is
        // text drawn by render(), because a button's label cannot wrap - so the click target is
        // the box both lines sit in.
        nameWidth = Math.min(220, Math.max(120, guiW / 3));
        // Two lines and a little air, so the text block is centred in the box the button draws.
        nameHeight = (int) client.fonts().body().lineHeight(PROMPT_SCALE)
                + (int) client.fonts().body().lineHeight(NAME_SCALE) + 16;
        nameX = 8;
        nameY = Math.max(8, guiH - 8 - nameHeight);

        // The corner tray: two icon cells, bottom left. Its click target is the two cells
        // themselves (see {@link #cellAt}); the plate's frame is not a button, so a click on the
        // wood between them does nothing.
        tray = trayForGui(guiW, buttonHeight);
    }

    /**
     * The corner tray's geometry for a window, as a pure function.
     *
     * <p>Kept separate from {@link #init()} so the tray can be laid out for window sizes no
     * headless client can open. The corner is the part of this screen that has been wrong at three
     * different sizes, and "it looks right at the window I use" is what all three had in common.
     */
    static TrayLayout trayForGui(int guiWidth, int buttonHeight) {
        // In the menu's own band, not under the player board: the board and the menu column are
        // both anchored to the bottom of the window, so at a tall window the board's band is the
        // *top* of the screen and a board-anchored tray ended up below the bottom edge.
        return TrayLayout.forCorner(buttonHeight, menuGap(guiWidth));
    }

    /**
     * The room between the window's left edge and the menu column.
     *
     * <p>The tray's horizontal budget, and the reason its cells are sized from the window rather
     * than only from the buttons. The column is centred and 280 wide, or the window minus 24 when
     * the window is the narrower of the two.
     */
    static float menuGap(int guiWidth) {
        int buttonWidth = Math.min(280, guiWidth - 24);
        // The extra 2 is the gap the tray leaves before the column: at the default window the raw
        // difference is 65 units and the rounded tray came out one unit wider than that, so the
        // plate touched the 开始游戏 button's edge.
        return (guiWidth - buttonWidth) / 2F - 8F - 2F;
    }

    /**
     * The menu column's button height for a GUI height - {@code GuiLayout.fitHeight}'s result.
     *
     * <p>Here rather than inline because the tray's geometry is defined in terms of it, and a test
     * that hard-coded 27 or 68 would stop describing the rule the moment the menu changes.
     */
    static int menuButtonHeight(int guiHeight) {
        int titleReserve = Math.max(56, Math.min(140, guiHeight * 30 / 100));
        return GuiLayout.fitHeight(guiHeight, 68, 4, titleReserve, 8);
    }

    /**
     * The player board is clicked by the screen, not by a widget.
     *
     * <p>It is one button-shaped box with two lines of text on it, and both have to sit inside
     * the box: a {@code Button} draws exactly one label, centred, and two labels drawn around it
     * by hand end up outside it. So the sprite and the two lines are drawn here and the screen
     * takes the click - {@code Screen.onMouseClicked} is the documented place for a screen's own
     * click regions.
     */
    private boolean overPlayerBoard(double guiX, double guiY) {
        return guiX >= nameX && guiX < nameX + nameWidth
                && guiY >= nameY && guiY < nameY + nameHeight;
    }

    /**
     * Which corner cell a GUI point is on: 0 the shop, 1 the packs page, -1 neither.
     *
     * <p>The tray is drawn from these same rectangles, so the plate and the hit box cannot drift
     * apart - the failure mode the player board already has a comment about.
     */
    private int cellAt(double guiX, double guiY) {
        for (int i = 0; i < 2; i++) {
            if (guiX >= tray.cellX(i) && guiX < tray.cellX(i) + tray.cellWidth()
                    && guiY >= tray.cellY() && guiY < tray.cellY() + tray.cellHeight()) {
                return i;
            }
        }
        return -1;
    }


    /**
     * The read-only view the tray's test drives.
     *
     * <p>The tray is drawn and hit-tested from the same two rectangles, and nothing outside this
     * class can name them - which is the point (a hit box declared a second time is a hit box that
     * drifts). But "clicking the corner cell opens the shop" is a contract about this screen, so
     * the test needs the rectangles rather than a guessed pair of coordinates: one that hard-coded
     * 30,18 would keep passing after the tray moved and would be testing nothing.
     *
     * <p>Reads the fields rather than calling {@code init()}: a windowless client cannot open the
     * font that the tray's earlier version measured its cells with. There is deliberately no label
     * on a cell to expose - the two are icon-only, and the text lives in the hover tip.
     */
    public record TrayCell(float centerX, float centerY) {
    }

    /**
     * Where a corner cell is, in GUI units.
     *
     * <p>Public for the smoke driver's {@code pvzce.smokeTray} hook, which has to click one: the
     * tray is anchored to the player board's font-measured height and scales with the menu buttons,
     * so its coordinates are the one pair on this screen that cannot be written down as constants.
     * Aiming with the same method {@link #cellAt} hit-tests with is also what makes that hook a
     * check of the two agreeing.
     */
    public TrayCell trayCell(int index) {
        return cellOf(tray, index);
    }

    /**
     * The centre of a cell, for a layout.
     *
     * <p>Static and taking the layout because the click contract has to be testable without a
     * window: a screen's {@code init()} reads the framebuffer, so a windowless test can only reach
     * the geometry through {@link #trayForGui}.
     */
    static TrayCell cellOf(TrayLayout layout, int index) {
        return new TrayCell(layout.cellX(index) + layout.cellWidth() / 2F,
                layout.cellY() + layout.cellHeight() / 2F);
    }


    /** The tray's own rectangle, for the "a click on the wood does nothing" half of the contract. */
    float[] trayBounds() {
        return new float[]{tray.x(), tray.y(), tray.width(), tray.height()};
    }


    /**
     * Opens the picker over the menu.
     *
     * <p>The dialog only switches who is playing; the board behind it redraws on the next frame,
     * which is the feedback that the click did something. It deliberately does not open the level
     * list - that is the 开始游戏 button's job, and two gestures that both navigate from here
     * would make "change player" and "start playing" the same click.
     */
    private void openPlayerPicker() {
        showDialog(PlayerPickerDialog.create(client, world -> client.setCurrentWorld(world)));
    }

    /**
     * The 开始游戏 button: the player already chosen, or the picker if there is none.
     *
     * <p>Not "open the picker every time": the name is on screen, and asking again after every
     * level would be asking a question the player has already answered. The name stays one click
     * away on the player board when they do want to change it.
     */
    private void enterCurrentPlayer() {
        String current = client.currentWorld();
        if (current == null || current.isBlank()
                || !WorldPaths.worldDir(client.gameDir(), current).toFile().isDirectory()) {
            openPlayerPicker();
            return;
        }
        client.openScreen(new LevelSelectScreen(client));
    }

    @Override
    public void render() {
        client.beginGuiView();
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        renderBackground(0.16F, 0.3F, 0.13F);

        if (client.hasTexture(TITLE_LOGO)) {
            // Keep the 2170x725 aspect ratio inside the title area above the buttons. The area is
            // measured from the menu block's top edge, so the logo stops growing at the same line
            // whichever comes first - and the corner tray (which reaches higher than the menu
            // does) is not in this column, so it cannot be overlapped.
            float availableHeight = Math.max(24F, guiH - buttonTop - 12F);
            float maxWidth = guiW * 0.72F;
            float logoHeight = Math.min(availableHeight * 0.9F, maxWidth / TITLE_LOGO_ASPECT);
            float logoWidth = logoHeight * TITLE_LOGO_ASPECT;
            float logoX = (guiW - logoWidth) / 2F;
            float logoY = buttonTop + (availableHeight - logoHeight) / 2F;
            client.drawTexture(TITLE_LOGO, logoX, logoY, logoWidth, logoHeight, 0.1F, 1F, 1F, 1F, 1F);
        } else {
            String title = "PVZ 社区版";
            client.fonts().button().draw(title, (guiW - client.fonts().button().width(title, titleScale)) / 2F,
                    titleY, titleScale, 1F, 0.92F, 0.35F, 1F);
            String subtitle = "植物大战僵尸 · 社区版";
            client.fonts().body().draw(subtitle, (guiW - client.fonts().body().width(subtitle, subtitleScale)) / 2F,
                    subtitleY, subtitleScale, 0.85F, 0.95F, 0.85F, 1F);
        }

        renderPlayerBoard();
        renderTray();

        // The build line, bottom right: it used to run along the bottom edge from behind the
        // player board, which is where the tray now is. Right-aligned to the same 8-unit margin
        // the rest of the screen uses.
        String loaderVersion = FabricLoader.getInstance().getModContainer("fabricloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String versionType = System.getProperty("pvzce.versionType", "release");
        String info = "PVZCE 1.0.0 · fabricloader " + loaderVersion + " · "
                + FabricLoader.getInstance().getAllMods().size() + " mods · " + versionType;
        client.fonts().body().draw(info, guiW - 8F - client.fonts().body().width(info, 0.7F), 8, 0.7F,
                0.7F, 0.75F, 0.7F, 1F);
        for (var widget : widgets) {
            widget.render(client);
        }
        renderServerMessage();
    }

    /**
     * The corner tray: a wooden plate with two icon cells, the shop and the packs page.
     *
     * <p>They are not menu rows any more. A row in the middle column is where the game's four
     * *actions* live, and the shop and the packs page are places the player visits and comes back
     * from - which is what the bottom-left corner is for. Drawing them as cells also lets them
     * carry an icon, and a coin and a seed packet say what the two pages are.
     *
     * <p>No labels on the cells, only a hover tip: the corner is 66 units wide at the default
     * window and a cell with a name does not fit (see the constants above). The plate behind each
     * icon is the level list's wooden button, stretched - it is what makes the two read as buttons
     * rather than as two icons dropped on the background.
     */
    private void renderTray() {
        NinePatch.drawNineSlice(client, TRAY_PANEL, tray.x(), tray.y(),
                tray.width(), tray.height(), 0F,
                TRAY_TEXTURE_WIDTH, TRAY_TEXTURE_HEIGHT,
                9F, 9F, 32F, 32F, 1F, 1F, 1F, 1F);
        int hovered = cellAt(client.guiMouseX(client.window().cursorX()),
                client.guiMouseY(client.window().cursorY()));
        drawCell(0, SHOP_ICON, GuiLang.raw("gui.pvzce.shop.title", "商店"), hovered == 0);
        drawCell(1, PACKS_ICON, GuiLang.raw("gui.pvzce.packs.title", "数据包"), hovered == 1);
    }

    /**
     * One tray cell: the plate, the icon standing on it, and the tip while it is hovered.
     *
     * <p>The tip is drawn here rather than by a widget because the tip follows the cursor and the
     * cell does not: {@link HoverTip} is the game's one tooltip, and its callers all draw it from
     * their own render pass (the card bar and the buff row do the same).
     */
    private void drawCell(int index, Identifier icon, String label, boolean lit) {
        float x = tray.cellX(index);
        float y = tray.cellY();
        float plateH = tray.plateHeight();
        client.drawTexture(CELL_PLATE, x, y, tray.cellWidth(), plateH, 0F, 1F, 1F, 1F, 1F);
        // Hover is a warm wash over the plate rather than the disabled art: that image is the
        // same wood drained to grey, which reads as "not available" on a cell that is.
        if (lit) {
            client.drawSolid(x, y, tray.cellWidth(), plateH, 0.05F, 1F, 0.85F, 0.45F, 0.18F);
        }
        drawIcon(icon, x, y + plateH + 1F, tray.cellWidth(), tray.iconBox());
        if (lit) {
            HoverTip.draw(client, label,
                    (float) client.guiMouseX(client.window().cursorX()),
                    (float) client.guiMouseY(client.window().cursorY()), 1F);
        }
    }

    /**
     * Draws an icon at its own aspect inside a box.
     *
     * <p>Not stretched to the box: the seed packet is 100x140 and the coin is 45x45, and a packet
     * squashed into a square is a different piece of art. Fitted instead, both sit in the same cell
     * as themselves.
     */
    private void drawIcon(Identifier icon, float x, float y, float boxWidth, float boxHeight) {
        if (!client.hasTexture(icon)) {
            client.warnMissingTexture(icon);
            return;
        }
        float box = Math.min(boxWidth, boxHeight);
        var tex = client.textures().getOrLoad(icon);
        float aspect = tex.width() / (float) Math.max(1, tex.height());
        float width = aspect >= 1F ? box : box * aspect;
        float height = aspect >= 1F ? box / aspect : box;
        client.drawTexture(icon, x + (boxWidth - width) / 2F, y + (boxHeight - height) / 2F,
                width, height, 0.5F, 1F, 1F, 1F, 1F);
    }

    /**
     * The last line the server pushed, for five seconds.
     *
     * <p>Over the middle of the menu, the way the shop draws its own answer, and drawn after the
     * widgets so it is not hidden by them: at the default window every corner is either a button,
     * the player board or the logo, and a message that lands behind one of those is the same as
     * no message at all. This is the menu's only channel for a server answer - a refusal that
     * arrives while the player is in a menu has no level HUD to land on.
     */
    private void renderServerMessage() {
        String message = client.recentServerMessage();
        if (message.isEmpty()) {
            return;
        }
        float scale = 1.15F;
        client.fonts().button().draw(message,
                (client.guiWidth() - client.fonts().button().width(message, scale)) / 2F,
                client.guiHeight() / 2F + 10F, scale, 1F, 0.45F, 0.4F, 1F);
    }

    /** The wooden plate the player board is drawn on; the same one the menu buttons use. */
    private static final Identifier PLAYER_BOARD =
            Identifier.withDefaultNamespace("textures/gui/screen/seeds/seed_chooser_button");

    /** The two lines' sizes, and the one place the block's height is derived from. */
    private static final float PROMPT_SCALE = 1.15F;
    private static final float NAME_SCALE = 0.95F;

    /**
     * The player board: a wooden plate with the question and the current name on it.
     *
     * <p>Not a widget. A {@link Button} draws exactly one label, centred, and this is two lines
     * of different sizes that both have to sit inside the plate - so the plate is drawn here, the
     * two lines are centred on it as a pair, and the screen takes the click
     * ({@link #onMouseClicked}, the documented place for a screen's own click regions). The box
     * is sized from the same two line heights, so the words cannot drift out of it.
     */
    private void renderPlayerBoard() {
        client.drawTexture(PLAYER_BOARD, nameX, nameY, nameWidth, nameHeight, 0.05F, 1F, 1F, 1F, 1F);
        if (overPlayerBoard(client.guiMouseX(client.window().cursorX()),
                client.guiMouseY(client.window().cursorY()))) {
            client.drawSolid(nameX, nameY, nameWidth, nameHeight, 0.06F, 1F, 1F, 1F, 0.14F);
        }
        float nameLine = client.fonts().body().lineHeight(NAME_SCALE);
        float promptLine = client.fonts().body().lineHeight(PROMPT_SCALE);
        float top = nameY + (nameHeight - nameLine - promptLine) / 2F;
        // The name over the question: the question is the plate's caption, and the name is the
        // answer the player is looking for.
        client.fonts().body().draw(playerName(), nameX + 8, top, NAME_SCALE, 1F, 1F, 1F, 1F);
        client.fonts().body().draw("谁要玩游戏？", nameX + 8, top + nameLine, PROMPT_SCALE,
                1F, 0.95F, 0.6F, 1F);
    }

    /** The player this session would play as, as the board shows it. */
    private String playerName() {
        String current = client.currentWorld();
        return current == null || current.isBlank() ? "（未选择）" : current;
    }
}
