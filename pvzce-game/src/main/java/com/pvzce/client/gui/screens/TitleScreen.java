package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.common.util.WorldPaths;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
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
        // Six entries now: the shop sits second, where the original puts Crazy Dave's counter -
        // right after "start playing" - and the packs page follows it, because both are the
        // menu's "what is in my game" pages.
        int buttonHeight = GuiLayout.fitHeight(guiH, 68, 6, titleReserve, 8);
        int gap = GuiLayout.gapFor(buttonHeight);
        int blockHeight = 6 * buttonHeight + 5 * gap;

        // Buttons are anchored to the bottom so the menu fills the screen and
        // no large blank strip remains under the last button.
        int blockTop = 8 + blockHeight;
        buttonTop = blockTop;
        // The labels are language keys now rather than literals: this array is where the menu's
        // text lives, and it was the last place in this file where a translation could not
        // reach. The shop's and the packs page's keys are theirs - the button and the page say
        // the same word on purpose.
        String[] labels = {
                GuiLang.raw("gui.pvzce.title.start", "Start playing"),
                GuiLang.raw("gui.pvzce.shop.title", "Shop"),
                GuiLang.raw("gui.pvzce.packs.title", "Packs"),
                GuiLang.raw("gui.pvzce.title.mods", "Mods"),
                GuiLang.raw("gui.pvzce.title.settings", "Settings"),
                GuiLang.raw("gui.pvzce.title.quit", "Quit")
        };
        Runnable[] actions = {
                this::enterCurrentPlayer,
                () -> client.openScreen(new ShopScreen(client)),
                () -> client.openScreen(new PackScreen(client)),
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
        if (button == 0 && overPlayerBoard(guiX, guiY)) {
            openPlayerPicker();
        }
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
            // Keep the 2170x725 aspect ratio inside the title area above the buttons.
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

        String loaderVersion = FabricLoader.getInstance().getModContainer("fabricloader")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
        String versionType = System.getProperty("pvzce.versionType", "release");
        String info = "PVZCE 1.0.0 · fabricloader " + loaderVersion + " · "
                + FabricLoader.getInstance().getAllMods().size() + " mods · " + versionType;
        client.fonts().body().draw(info, nameX + nameWidth + 8, 8, 0.7F, 0.7F, 0.75F, 0.7F, 1F);
        for (var widget : widgets) {
            widget.render(client);
        }
        renderServerMessage();
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
