package com.pvzce.client;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.screens.EditorScreen;
import com.pvzce.client.gui.screens.LevelSelectScreen;
import com.pvzce.client.gui.screens.TitleScreen;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelRewardS2C;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.stb.STBImageWrite;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * The {@code pvzce.smoke*} development harness: drive the game to a screen and photograph it.
 *
 * <p>Every switch here is a {@code -D} property read once at construction; the complete table,
 * the two traps (a locked level fails silently, and {@code -Ppvzce.gameDir} is not optional) and
 * a set of worked examples live in {@code docs/冒烟与截图指南.md} - that file is the contract, this
 * class is the implementation.
 *
 * <p>It is a separate class because it is not part of the game: {@link PvzceClient#run} used to
 * carry all of this inline, which made the frame loop read as one third harness. Nothing here
 * decides anything the player would see unless a property asked for it - with no properties set,
 * {@link #applyInitialScreen} opens the title screen and the other hooks return immediately.
 *
 * <p>The one deliberate exception to the logging rule: the {@code [SMOKE]} lines go to stdout
 * rather than through slf4j, because they are a protocol between this class and the shell scripts
 * that run it - a frame number and a screen name, with no timestamp in front of them.
 */
final class SmokeDriver {
    private final PvzceClient client;

    private final int captureFrame = Integer.getInteger("pvzce.captureFrame", -1);
    private final String capturePath = System.getProperty("pvzce.capturePath");
    private final int smokeFrames = Integer.getInteger("pvzce.smokeFrames", 0);
    private final String smokeLevel = System.getProperty("pvzce.smokeLevel", "");
    private final boolean smokeLevelRestart = Boolean.parseBoolean(
            System.getProperty("pvzce.smokeLevelRestart", "true"));
    private final String smokeSeedLevel = System.getProperty("pvzce.smokeSeedLevel", "");
    /** Opens the editor for an existing level; UI smoke tests and screenshots. */
    private final String smokeEditorLevel = System.getProperty("pvzce.smokeEditor", "");
    /** Opens the editor for a brand-new level id, exercising the create path. */
    private final String smokeNewEditor = System.getProperty("pvzce.smokeNewEditor", "");
    /** Which editor page to switch to, by its lang key suffix (rule/wave/info/...). */
    private final String smokeEditorPage = System.getProperty("pvzce.smokeEditorPage", "");
    /** Opens the wave table on top of the editor. */
    private final boolean smokeWaveEditor = Boolean.getBoolean("pvzce.smokeWaveEditor");
    /** Saves the editor once, so a smoke run can verify the write round trip. */
    private final boolean smokeSave = Boolean.getBoolean("pvzce.smokeSave");
    /** Places presets on the editor board: {@code kind=id@x,y;kind=id@x,y}. */
    private final String smokePlace = System.getProperty("pvzce.smokePlace", "");
    private boolean smokeLevelRequested;
    /**
     * Smoke hook: treat the {@code smokeLevel} request as a direct fresh run, so a level's
     * opening dialogue plays in game.
     *
     * <p>Off by default because the smoke level is normally opened to photograph the
     * board; a developer who wants the in-game dialogue photographs it with this on.
     */
    private final boolean smokeDialogue = Boolean.getBoolean("pvzce.smokeDialogue");
    /**
     * Synthesises a finished level's payout, for screenshots of the reward flow.
     *
     * <p>{@code unlock} lands a seed packet, {@code money} the money bag. The two
     * packets this replaces (a plant win, then {@code LevelRewardS2C}) are covered by
     * tests; what cannot be tested is whether the drop is visible, clickable, and
     * lands on the award page, and that is what this drives.
     */
    private final String smokeReward = System.getProperty("pvzce.smokeReward", "");
    private boolean smokeRewardFired;
    private boolean smokeSeedListRequested;
    private boolean smokeSeedOpened;
    private boolean smokeEditorOpened;
    /** A GUI point to click, as {@code x,y} in logical GUI coordinates. */
    private final double[] smokeClickAt = parsePoint(System.getProperty("pvzce.smokeClick", ""));
    /**
     * Development smoke hook: drag from {@code pvzce.smokeClick} to this GUI point and let go.
     *
     * <p>A press and a release at the same pixel is a click, so {@code smokeClick} alone
     * cannot exercise a drag gesture - and dragging is now how a card gets to a cell. The
     * press happens at {@code smokeClickFrame}, the drag and release here.
     */
    private final double[] smokeDragTo = parsePoint(System.getProperty("pvzce.smokeDragTo", ""));
    private final int smokeDragFrame = Integer.getInteger("pvzce.smokeDragFrame", 60);
    private boolean smokeDragDone;
    /**
     * Smoke hook: keep the pointer at this GUI point, so hover states can be photographed.
     *
     * <p>{@code smokeClick} delivers a click at a point without moving the pointer, and half
     * the HUD is about where the pointer <em>is</em> - a cell highlight, the ghost of the
     * plant about to be placed. This warps the real cursor (the platform's own call, so the
     * window receives a normal move event) from {@code smokeHoverFrame} onwards, every
     * frame, because a screen that rebuilds itself can lose a one-shot move.
     */
    private final double[] smokeHoverAt = parsePoint(System.getProperty("pvzce.smokeHover", ""));
    private final int smokeHoverFrame = Integer.getInteger("pvzce.smokeHoverFrame", 3);
    private final int smokeClickFrame = Integer.getInteger("pvzce.smokeClickFrame", 45);
    /**
     * How many times {@code smokeClick} repeats, and how many frames apart.
     *
     * <p>A conversation is advanced one click at a time, and so is a card bar that has to
     * be filled before a level can start: one click cannot photograph the far side of
     * either. The default is one click, which is what the hook always did.
     */
    private final int smokeClickRepeat = Integer.getInteger("pvzce.smokeClickRepeat", 1);
    private final int smokeClickPeriod = Integer.getInteger("pvzce.smokeClickPeriod", 12);
    private int smokeClicksSent;
    /**
     * {@code -Dpvzce.traceInput=true}: what the synthetic input is aimed at.
     *
     * <p>Trace, not test: it is the only way to see where a {@code smokeClick} actually landed,
     * short of a screenshot - and a click that misses is silent by construction.
     */
    private final boolean traceInput = Boolean.getBoolean("pvzce.traceInput");
    /**
     * Commands to run once a level is up, separated by {@code |}.
     *
     * <p>For smoke runs that need to put something specific on the board - a drop with a
     * particular motion, a plant with something on it - which the level file cannot
     * express without becoming a fixture that other tests depend on. Runs the real command
     * path, so what is screenshotted is what a player could produce.
     *
     * <p>Write a space as {@code +}: {@code pvzce.smoke} itself is a whitespace-separated
     * list, so {@code smokeCommands=/spawn+resource+pvzce:sun+3+1}.
     */
    private final String smokeCommands = System.getProperty("pvzce.smokeCommands", "");
    private boolean smokeCommandsSent;
    /** A dialog button to click, found by its label. */
    private final String smokeClickLabel = System.getProperty("pvzce.smokeClickLabel", "");
    private boolean smokeClickDone;
    private boolean smokeEditorHookDone;

    SmokeDriver(PvzceClient client) {
        this.client = client;
    }

    /**
     * {@code pvzce.smokeScreen}: which screen the run opens on.
     *
     * <p>Anything unrecognised - including no property at all - opens the title screen, so a
     * normal run and a mistyped smoke run behave the same way.
     */
    void applyInitialScreen() {
        String smokeScreen = System.getProperty("pvzce.smokeScreen", "");
        if ("mods".equals(smokeScreen)) {
            client.setScreenReplacing(new com.pvzce.client.gui.mods.ModsScreen(client));
        } else if ("settings".equals(smokeScreen)) {
            client.setScreenReplacing(new com.pvzce.client.gui.screens.SettingsScreen(client));
        } else if ("console".equals(smokeScreen)) {
            // The console is an overlay, so the run needs a screen for it to float over.
            client.setScreenReplacing(new TitleScreen(client));
            client.openConsole("");
        } else if ("create".equals(smokeScreen)) {
            LevelSelectScreen levels = new LevelSelectScreen(client);
            client.setScreenReplacing(levels);
            com.pvzce.client.gui.screens.LevelCreateDialog create =
                    // Same callback LevelSelectScreen uses, so the smoke run exercises the
                    // real confirm -> editor path rather than a print-only stub.
                    com.pvzce.client.gui.screens.LevelCreateDialog.create(client, null, null, request -> {
                        System.out.println("[SMOKE] new-level dialog confirmed: " + request.id());
                        client.openNewLevelEditor(request.id(), request.name(),
                                request.width(), request.height());
                    });
            levels.showDialog(create);
        } else if ("levels".equals(smokeScreen)) {
            // The level list is where "new level" and "edit level" now live, and it needs
            // a level list from the server to show anything. The world has to be named:
            // the list is per world, and an unnamed one comes back empty.
            client.setCurrentWorld(System.getProperty("pvzce.smokeWorld", "world"));
            client.setScreenReplacing(new LevelSelectScreen(client));
            client.connection().send(new RequestLevelListC2S(client.currentWorld()));
        } else if ("title".equals(smokeScreen) || "main".equals(smokeScreen)) {
            client.setScreenReplacing(new TitleScreen(client));
        } else if ("inventory".equals(smokeScreen) || "backpack".equals(smokeScreen)) {
            client.setScreenReplacing(new com.pvzce.client.gui.screens.InventoryScreen(client));
        } else if ("award".equals(smokeScreen)) {
            // The award page only reads the reward packet, so a synthetic one is enough
            // to render it - winning a level to reach it would make the screen
            // unreachable for a screenshot run. Same spirit as the "create" key below,
            // which posts a real dialog with a stub confirm callback.
            client.setScreenReplacing(new com.pvzce.client.gui.screens.AwardScreen(client,
                    new LevelRewardS2C("pvzce:yard/adventure/1_1", 12, 100, 462, "pvzce:sunflower")));
        } else if ("award_money".equals(smokeScreen)) {
            // The other branch: nothing unlocked, so the frame shows the money bag.
            client.setScreenReplacing(new com.pvzce.client.gui.screens.AwardScreen(client,
                    new LevelRewardS2C("pvzce:yard/adventure/1_1", 0, 100, 462, "")));
        } else {
            client.setScreenReplacing(new TitleScreen(client));
        }
    }

    /**
     * The hooks that must run before this frame's screen tick: getting into a level, running
     * commands, and reaching the editor (which is four screens deep and no run could walk to).
     */
    void beforeFrame() {
        long clientTick = client.clientTick();
        // Development smoke hook: request a level automatically so CI can
        // render gameplay without driving the title/level screens.
        if (!smokeLevel.isBlank() && !smokeLevelRequested && clientTick > 2) {
            smokeLevelRequested = true;
            if (smokeDialogue) {
                client.requestFreshRunDirectly(smokeLevel, smokeLevelRestart);
            } else {
                client.requestLevel(smokeLevel, smokeLevelRestart);
            }
        }
        if (!smokeReward.isBlank() && !smokeRewardFired && clientTick > 90
                && client.currentScreen() instanceof com.pvzce.client.gui.screens.InGameScreen) {
            smokeRewardFired = true;
            String card = "unlock".equals(smokeReward) ? "pvzce:sunflower" : "";
            client.level().setGameState(com.pvzce.common.network.packet.GameStateS2C.WON,
                    "pvzce:plant_team");
            // A spot on the right of the lane rather than the middle, so the
            // screenshot proves the reward lands where it is told to.
            client.onLevelReward(new LevelRewardS2C("pvzce:yard/adventure/1_1", 12, 100, 462, card,
                    6.5F, 0F));
        }
        if (!smokeSeedLevel.isBlank() && !smokeSeedListRequested && clientTick > 2) {
            smokeSeedListRequested = true;
            client.connection().send(new RequestLevelListC2S(client.currentWorld()));
        }
        // Development smoke hook: drive the console once a level is actually running.
        if (!smokeCommands.isBlank() && !smokeCommandsSent
                && client.currentScreen() instanceof com.pvzce.client.gui.screens.InGameScreen) {
            smokeCommandsSent = true;
            for (String command : smokeCommands.split("\\|")) {
                // ``pvzce.smoke`` is split on whitespace, so a command's own spaces are
                // written as '+': the alternative is a Gradle property that silently
                // truncates every command at its first argument.
                String line = command.trim().replace('+', ' ');
                // And no leading slash: a console line is the command without one, so
                // "/spawn ..." would reach the parser as "//spawn ...".
                while (line.startsWith("/")) {
                    line = line.substring(1);
                }
                if (!line.isBlank()) {
                    client.connection().send(new CommandC2S(line));
                }
            }
        }
        // Development smoke hook for the editor: the editor is four screens deep
        // (title -> world -> level list -> editor), which no smoke run could
        // reach, so screenshots of it were impossible to automate.
        if (!smokeEditorOpened && clientTick > 20 && client.currentScreen() instanceof TitleScreen) {
            if (!smokeEditorLevel.isBlank()) {
                smokeEditorOpened = true;
                Identifier editorId = Identifier.tryParse(smokeEditorLevel);
                if (editorId != null) {
                    client.openEditor(editorId);
                }
            } else if (!smokeNewEditor.isBlank()) {
                smokeEditorOpened = true;
                Identifier editorId = Identifier.tryParse(smokeNewEditor);
                if (editorId != null) {
                    client.openNewLevelEditor(editorId, "冒烟测试关卡", 9, 5);
                }
            }
        }
        if (smokeEditorOpened && !smokeEditorHookDone && clientTick > 40
                && client.currentScreen() instanceof EditorScreen editor) {
            if (smokeWaveEditor) {
                // The wave editor is a page now, so "open the wave editor" is a page
                // switch rather than a screen push.
                if (clientTick > 60) {
                    smokeEditorHookDone = true;
                    editor.showPageForSmoke("wave");
                }
            } else {
                // Placement happens before the save/page switch: a run that asks for
                // both expects the board it described to be what gets written.
                if (!smokePlace.isBlank()) {
                    // "kind=id@x,y;kind=id@x,y"
                    for (String placement : smokePlace.split(";")) {
                        int at = placement.indexOf('@');
                        int eq = placement.indexOf('=');
                        if (eq < 0 || at < 0) {
                            continue;
                        }
                        String[] cell = placement.substring(at + 1).split(",");
                        editor.placeForSmoke(placement.substring(0, eq),
                                placement.substring(eq + 1, at),
                                Integer.parseInt(cell[0].trim()), Integer.parseInt(cell[1].trim()));
                    }
                }
                if (smokeSave) {
                    smokeEditorHookDone = true;
                    editor.saveForSmoke();
                } else if (!smokeEditorPage.isBlank()) {
                    smokeEditorHookDone = true;
                    editor.showPageForSmoke(smokeEditorPage);
                } else if (!smokePlace.isBlank()) {
                    smokeEditorHookDone = true;
                }
            }
        }
    }

    /**
     * The hooks that run after this frame has been rendered.
     *
     * @return true when the run is over ({@code pvzce.smokeFrames} reached), so the loop stops
     */
    boolean afterFrame(Screen screen) {
        long clientTick = client.clientTick();
        // Development smoke hook: click a GUI point given in logical GUI coordinates,
        // so a dialog's buttons can be exercised without a human at the mouse.
        // Click a dialog button by its label: dialog geometry depends on the window
        // size, so a hard-coded point is easy to get wrong and silently misses.
        if (!smokeClickLabel.isBlank() && !smokeClickDone && clientTick == smokeClickFrame) {
            for (com.pvzce.client.gui.components.Dialog dialog : client.currentScreen().dialogs()) {
                for (com.pvzce.client.gui.components.AbstractWidget child : dialog.children()) {
                    if (child instanceof com.pvzce.client.gui.components.Button button
                            && smokeClickLabel.equals(button.label())) {
                        smokeClickDone = true;
                        int cx = child.x() + child.width() / 2;
                        int cy = child.y() + child.height() / 2;
                        System.out.println("[SMOKE] clicking '" + smokeClickLabel + "' at " + cx + "," + cy);
                        client.deliverGuiClick(cx, cy, 0);
                    }
                }
            }
        }
        if (smokeClickAt != null && smokeClicksSent < Math.max(1, smokeClickRepeat)
                && clientTick >= smokeClickFrame
                && (clientTick - smokeClickFrame) % Math.max(1, smokeClickPeriod) == 0) {
            smokeClicksSent++;
            smokeClickDone = true;
            double[] gui = smokeClickAt;
            double rawX = gui[0] * client.window().width() / (double) Math.max(1, client.guiWidth());
            double rawY = client.window().height()
                    - gui[1] * client.window().height() / (double) Math.max(1, client.guiHeight());
            if (traceInput) {
                System.out.println("[SMOKE] click gui=" + gui[0] + "," + gui[1]
                        + " raw=" + rawX + "," + rawY
                        + " window=" + client.window().width() + "x" + client.window().height()
                        + " gui=" + client.guiWidth() + "x" + client.guiHeight()
                        + " screen=" + (client.currentScreen() == null ? "none"
                                : client.currentScreen().getClass().getSimpleName()));
            }
            client.deliverRawClick(rawX, rawY, 0);
        }
        if (smokeHoverAt != null && clientTick >= smokeHoverFrame) {
            // The same conversion smokeClick uses, and for the same reason: the cursor is
            // reported top-down while GUI Y grows upwards, so a point that is 40% up the
            // GUI is 60% down the window. Getting this backwards points the "hover" at the
            // vertical mirror of the cell under test, which is exactly the kind of
            // off-by-a-reflection a screenshot is supposed to catch.
            double rawX = smokeHoverAt[0] * client.window().width()
                    / (double) Math.max(1, client.guiWidth());
            double rawY = client.window().height()
                    - smokeHoverAt[1] * client.window().height() / (double) Math.max(1, client.guiHeight());
            client.window().warpCursor(rawX, rawY);
        }
        if (smokeDragTo != null && !smokeDragDone && clientTick == smokeDragFrame) {
            smokeDragDone = true;
            double[] gui = smokeDragTo;
            double rawX = gui[0] * client.window().width() / (double) Math.max(1, client.guiWidth());
            double rawY = client.window().height()
                    - gui[1] * client.window().height() / (double) Math.max(1, client.guiHeight());
            System.out.println("[SMOKE] dragging to gui=" + gui[0] + "," + gui[1]
                    + " backToGui=" + client.guiMouseX(rawX) + "," + client.guiMouseY(rawY));
            client.deliverRawDrag(rawX, rawY, 0);
            client.deliverRawRelease(rawX, rawY, 0);
        }
        if (smokeFrames > 0 && clientTick == smokeFrames) {
            System.out.println("[SMOKE] frame " + smokeFrames + " rendered, screen="
                    + screen.getClass().getSimpleName());
            return true;
        }
        if (capturePath != null && clientTick == captureFrame) {
            capture(capturePath);
        }
        return false;
    }

    /**
     * {@code pvzce.smokeSeedLevel}: open the seed chooser for that level as soon as the list
     * names it, so a screenshot run does not have to walk the list and click a row.
     *
     * @return true when this call opened the chooser
     */
    boolean openSeedChooserForSmoke(List<LevelListS2C.LevelInfo> levels) {
        if (smokeSeedLevel.isBlank() || smokeSeedOpened) {
            return false;
        }
        for (LevelListS2C.LevelInfo info : levels) {
            if (smokeSeedLevel.equals(info.id())) {
                smokeSeedOpened = true;
                client.openSeedSelection(info, false);
                return true;
            }
        }
        return false;
    }

    private void capture(String path) {
        int w = client.window().width();
        int h = client.window().height();
        ByteBuffer pixels = BufferUtils.createByteBuffer(w * h * 4);
        GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        ByteBuffer flipped = BufferUtils.createByteBuffer(w * h * 4);
        byte[] row = new byte[w * 4];
        for (int y = h - 1; y >= 0; y--) {
            pixels.get(w * y * 4, row, 0, w * 4);
            flipped.put(row);
        }
        flipped.flip();
        STBImageWrite.stbi_flip_vertically_on_write(false);
        if (!STBImageWrite.stbi_write_png(path, w, h, 4, flipped, w * 4)) {
            System.out.println("[SMOKE] failed to write screenshot " + path);
        }
    }

    /** Parses {@code x,y} in logical GUI coordinates, or null when it is absent or malformed. */
    private static double[] parsePoint(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
