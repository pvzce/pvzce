package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.layout.GuiLayout;
import com.pvzce.common.network.packet.LevelListS2C;

/**
 * Pre-game screen: the plant_or_zombie background already contains a left and
 * a right panel, so the panels themselves are the team buttons.
 */
public final class LevelSetupScreen extends Screen {
    private static final Identifier BACKGROUND = Identifier.withDefaultNamespace("textures/gui/screen/level/plant_or_zombie");
    private static final Identifier LOCK_ICON = Identifier.withDefaultNamespace("textures/gui/icon/lock");

    private final LevelListS2C.LevelInfo levelInfo;
    private String selectedTeam = "";
    private LevelListS2C.TeamInfo plantTeam;
    private LevelListS2C.TeamInfo zombieTeam;
    private boolean plantUnlocked;
    private boolean zombieUnlocked;
    private int plantX;
    private int plantY;
    private int plantWidth;
    private int plantHeight;
    private int zombieX;
    private int zombieY;
    private int zombieWidth;
    private int zombieHeight;

    public LevelSetupScreen(PvzceClient client, LevelListS2C.LevelInfo levelInfo) {
        super(client);
        this.levelInfo = levelInfo;
    }

    @Override
    protected Identifier backgroundTexture() {
        return BACKGROUND;
    }

    @Override
    protected boolean backgroundCover() {
        return true;
    }

    @Override
    protected void init() {
        client.music().ensureMenu("pvzce:music/choose_your_seeds");

        plantTeam = findTeam("plant");
        zombieTeam = findTeam("zombie");
        if (plantTeam == null && !levelInfo.teams().isEmpty()) {
            plantTeam = levelInfo.teams().get(0);
        }
        if (zombieTeam == null && levelInfo.teams().size() > 1) {
            zombieTeam = levelInfo.teams().get(1);
        }
        plantUnlocked = plantTeam != null && plantTeam.playable();
        // Who may be played is the level's own declaration, sent with the list as a verdict per
        // team. This screen used to decide for itself that the zombie side was never playable,
        // which made "this level is plants only" a fact about the build rather than about the
        // level - and gave the author no way to say otherwise.
        zombieUnlocked = zombieTeam != null && zombieTeam.playable();
        // Default to a side that can actually be played, so a level whose only playable team is
        // not the first one does not open with a padlocked panel selected.
        selectedTeam = firstPlayableId();

        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        int titleReserve = Math.max(56, Math.min(110, guiH * 32 / 100));
        int actionHeight = GuiLayout.fitHeight(guiH, 67, 1, titleReserve, 0);
        int actionWidth = Math.min(210, (guiW - 24) / 2);
        int startX = centerX(actionWidth * 2 + 8);
        addWidget(new Button(startX, 4, actionWidth, actionHeight, "开始游戏", this::startGame)
                .style(Button.Style.SEED_CHOOSER));
        addWidget(new Button(startX + actionWidth + 8, 4, actionWidth, actionHeight, "返回", this::requestClose)
                .style(Button.Style.SEED_CHOOSER));

        int texWidth = 800;
        int texHeight = 600;
        try {
            var texture = client.textures().getOrLoad(BACKGROUND);
            texWidth = texture.width();
            texHeight = texture.height();
        } catch (RuntimeException ignored) {
        }
        // Same transform the background is drawn with, so the clickable panels and
        // the image can never disagree.
        CoverFit fit = coverFit(texWidth, texHeight);
        float scale = fit.scale();

        // Panel rectangles measured from plant_or_zombie.png (800x600), top-down y.
        plantX = Math.round(fit.mapX(20));
        plantY = Math.round(fit.mapY(168 + 258, texHeight));
        plantWidth = Math.round(378 * scale);
        plantHeight = Math.round(258 * scale);

        zombieX = Math.round(fit.mapX(405));
        zombieY = Math.round(fit.mapY(168 + 261, texHeight));
        zombieWidth = Math.round(371 * scale);
        zombieHeight = Math.round(261 * scale);
    }

    private LevelListS2C.TeamInfo findTeam(String keyword) {
        return levelInfo.teams().stream()
                .filter(team -> team.id().toLowerCase(java.util.Locale.ROOT).contains(keyword))
                .findFirst()
                .orElse(null);
    }

    /**
     * The team to start on: the first one the level offers, then the win team, then nothing.
     *
     * <p>The panels are still drawn whichever way this lands - the point is that a level naming
     * two playable sides opens on the first of them rather than on whichever one happens to be
     * on the left.
     */
    private String firstPlayableId() {
        for (LevelListS2C.TeamInfo team : levelInfo.teams()) {
            if (team.playable()) {
                return team.id();
            }
        }
        return levelInfo.winTeam();
    }

    /**
     * Starts the run.
     *
     * <p>Which panel is highlighted is the player's answer to "who do you want to be", and it is
     * sent with the entry packet ({@code humanTeam}) rather than being a highlight: the level
     * declares which sides may be played, and a versus level may be played from either end. Only
     * the fallback below - a level that names a playable team the screen could not match to a
     * panel - goes in without an answer, and then the level's own default side stands.
     */
    private void startGame() {
        if (plantTeam == null) {
            return;
        }
        // The highlighted panel is the answer, and it now travels: the server seats the player on
        // the side they picked, which also decides whose cards the bar holds and who wins.
        if (selectedTeam != null && !selectedTeam.isBlank()) {
            client.enterLevelFromMenu(levelInfo, selectedTeam);
            return;
        }
        // Same decision as the level list (and the only other place a level can be entered
        // from a menu): a level with a resumable save is loaded and asked about, never sent
        // through the seed chooser - its card bar is already in the save.
        client.enterLevelFromMenu(levelInfo);
    }

    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        if (button == 0 && plantTeam != null && inside(guiX, guiY, plantX, plantY, plantWidth, plantHeight)) {
            if (plantUnlocked) {
                selectedTeam = plantTeam.id();
            }
            return;
        }
        if (button == 0 && zombieTeam != null && inside(guiX, guiY, zombieX, zombieY, zombieWidth, zombieHeight)) {
            if (zombieUnlocked) {
                selectedTeam = zombieTeam.id();
            }
            return;
        }
    }

    private static boolean inside(double x, double y, int rx, int ry, int rw, int rh) {
        return x >= rx && x < rx + rw && y >= ry && y < ry + rh;
    }

    @Override
    public void render() {
        client.beginGuiView();
        renderBackground(0.09F, 0.18F, 0.11F);

        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        drawPanel(plantTeam, plantX, plantY, plantWidth, plantHeight, plantUnlocked);
        drawPanel(zombieTeam, zombieX, zombieY, zombieWidth, zombieHeight, zombieUnlocked);

        // A translucent strip keeps level info readable above the two panels.
        int titleReserve = Math.max(56, Math.min(110, guiH * 32 / 100));
        int stripBottom = guiH - titleReserve;
        int stripTop = guiH - 34;
        client.drawSolid(0, stripBottom, guiW, Math.max(0, stripTop - stripBottom),
                -0.5F, 0F, 0F, 0F, 0.45F);

        String title = "关卡准备";
        float scale = Math.min(2.2F, guiH / 100F);
        client.fonts().button().draw(title, (guiW - client.fonts().button().width(title, scale)) / 2F,
                guiH - client.fonts().button().lineHeight(scale) - 6, scale, 1F, 1F, 1F, 1F);

        String name = levelInfo.name() + " · " + levelInfo.id();
        float nameScale = 0.95F;
        client.fonts().body().draw(name, (guiW - client.fonts().body().width(name, nameScale)) / 2F,
                stripTop - 18, nameScale, 1F, 0.95F, 0.6F, 1F);
        if (!levelInfo.description().isEmpty()) {
            float descScale = 0.7F;
            client.fonts().body().draw(levelInfo.description(),
                    (guiW - client.fonts().body().width(levelInfo.description(), descScale)) / 2F,
                    stripTop - 34, descScale, 0.8F, 0.85F, 0.8F, 1F);
        }
        String status = statusLabel(levelInfo.status());
        if (!status.isEmpty()) {
            float statusScale = 0.75F;
            client.fonts().body().draw(status, (guiW - client.fonts().body().width(status, statusScale)) / 2F,
                    stripTop - 50, statusScale, 1F, 0.9F, 0.5F, 1F);
        }

        for (var widget : widgets) {
            widget.render(client);
        }
    }

    private void drawPanel(LevelListS2C.TeamInfo team, int x, int y, int width, int height, boolean unlocked) {
        if (team == null) {
            return;
        }
        boolean selected = selectedTeam != null && selectedTeam.equals(team.id());
        if (selected) {
            client.drawSolid(x, y, width, height, 0.3F, 1F, 0.9F, 0.2F, 0.20F);
            client.drawSolid(x, y, width, 3, 0.32F, 1F, 0.9F, 0.2F, 0.9F);
            client.drawSolid(x, y + height - 3, width, 3, 0.32F, 1F, 0.9F, 0.2F, 0.9F);
            client.drawSolid(x, y, 3, height, 0.32F, 1F, 0.9F, 0.2F, 0.9F);
            client.drawSolid(x + width - 3, y, 3, height, 0.32F, 1F, 0.9F, 0.2F, 0.9F);
        }

        float stripHeight = Math.min(38F, height * 0.18F);
        client.drawSolid(x, y, width, stripHeight, 0.25F, 0F, 0F, 0F, 0.55F);
        String label = team.name() + (unlocked ? "" : "（未解锁）");
        float labelScale = Math.min(1.2F, Math.max(0.7F, width / 220F));
        client.fonts().body().draw(label, x + (width - client.fonts().body().width(label, labelScale)) / 2F,
                y + (stripHeight - client.fonts().body().lineHeight(labelScale)) / 2F,
                labelScale, 1F, 1F, 1F, 1F);

        if (!unlocked) {
            float lockSize = Math.min(width, height) * 0.30F;
            client.drawTexture(LOCK_ICON, x + width - lockSize - 12, y + height - lockSize - 12,
                    lockSize, lockSize, 0.35F, 1F, 1F, 1F, 1F);
        }
    }

    private static String statusLabel(String status) {
        return com.pvzce.client.gui.GuiStatusText.detail(status);
    }

}
