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
     * <p>Sizes are therefore all small: a 104-wide plate whose 8-unit frame leaves just enough
     * interior for two 24-unit cells, and 22-unit icons. The plate is
     * {@code seed_chooser_background}, the game's own shallow box; the cells are the level list's
     * wooden plate, stretched.
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
    private static final float CELL_W = 24F;
    private static final float CELL_PLATE_H = 10F;
    private static final float CELL_ICON = 22F;
    private static final float CELL_GAP = 4F;
    private static final float TRAY_SIDE_PAD = 8F;
    private static final float TRAY_TOP_PAD = 6F;
    private static final float TRAY_BOTTOM_PAD = 6F;
    private static final float TRAY_W = 104F;
    private static final float TRAY_H = 72F;
    /**
     * One cell's height: the plate, the pixel of air above it, then the icon.
     *
     * <p>All three are constants, so this is one too - and it has to be, because the tray's
     * geometry is what the tray's test drives, and a headless client has no window to measure a
     * font through (asking the font made {@code init()} throw there).
     */
    private static final float CELL_H = CELL_PLATE_H + 1F + CELL_ICON;

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
    /** The corner tray's rectangle; the two cells inside it are derived from it. */
    private float trayX;
    private float trayY;
    private float trayWidth;
    private float trayHeight;

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

        // The corner tray: two icon cells, bottom left. Its geometry is derived here and its
        // click target is the two cells themselves (see {@link #cellAt}) - the tray's own frame
        // is not a button, so a click on the wood between the cells does nothing.
        trayX = 8;
        trayY = 8;
        trayWidth = TRAY_W;
        trayHeight = TRAY_H;
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

    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        if (button != 0) {
            return;
        }
        if (overPlayerBoard(guiX, guiY)) {
            openPlayerPicker();
            return;
        }
        int cell = cellAt(guiX, guiY);
        if (cell == 0) {
            client.openScreen(new ShopScreen(client));
        } else if (cell == 1) {
            client.openScreen(new PackScreen(client));
        }
    }

    /**
     * Which corner cell a GUI point is on: 0 the shop, 1 the packs page, -1 neither.
     *
     * <p>The tray is drawn from these same rectangles, so the plate and the hit box cannot drift
     * apart - the failure mode the player board already has a comment about.
     */
    private int cellAt(double guiX, double guiY) {
        for (int i = 0; i < 2; i++) {
            if (guiX >= cellX(i) && guiX < cellX(i) + CELL_W
                    && guiY >= cellY() && guiY < cellY() + CELL_H) {
                return i;
            }
        }
        return -1;
    }

    /** The left edge of cell {@code i}; the two sit side by side inside the plate. */
    private float cellX(int i) {
        return trayX + TRAY_SIDE_PAD + i * (CELL_W + CELL_GAP);
    }

    /** The bottom edge of both cells; the plate's padding is what lifts them off its frame. */
    private float cellY() {
        return trayY + TRAY_BOTTOM_PAD;
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
    record TrayCell(float centerX, float centerY) {
    }

    TrayCell trayCell(int index) {
        return new TrayCell(cellX(index) + CELL_W / 2F, cellY() + CELL_H / 2F);
    }

    /** The tray's own rectangle, for the "a click on the wood does nothing" half of the contract. */
    float[] trayBounds() {
        return new float[]{trayX, trayY, trayWidth, trayHeight};
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
        NinePatch.drawNineSlice(client, TRAY_PANEL, trayX, trayY, trayWidth, trayHeight, 0F,
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
        float x = cellX(index);
        float y = cellY();
        client.drawTexture(CELL_PLATE, x, y, CELL_W, CELL_PLATE_H, 0F, 1F, 1F, 1F, 1F);
        // Hover is a warm wash over the plate rather than the disabled art: that image is the
        // same wood drained to grey, which reads as "not available" on a cell that is.
        if (lit) {
            client.drawSolid(x, y, CELL_W, CELL_PLATE_H, 0.05F, 1F, 0.85F, 0.45F, 0.18F);
        }
        drawIcon(icon, x, y + CELL_PLATE_H + 1F, CELL_W);
        if (lit) {
            HoverTip.draw(client, label,
                    (float) client.guiMouseX(client.window().cursorX()),
                    (float) client.guiMouseY(client.window().cursorY()), 1F);
        }
    }

    /**
     * Draws an icon at its own aspect inside a square cell.
     *
     * <p>Not stretched to the square: the seed packet is 100x140 and the coin is 45x45, and a
     * packet squashed into a square is a different piece of art. Fitted instead, both sit in the
     * same cell as themselves.
     */
    private void drawIcon(Identifier icon, float x, float y, float box) {
        if (!client.hasTexture(icon)) {
            client.warnMissingTexture(icon);
            return;
        }
        var tex = client.textures().getOrLoad(icon);
        float aspect = tex.width() / (float) Math.max(1, tex.height());
        float width = aspect >= 1F ? box : box * aspect;
        float height = aspect >= 1F ? box / aspect : box;
        client.drawTexture(icon, x + (box - width) / 2F, y + (box - height) / 2F,
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
