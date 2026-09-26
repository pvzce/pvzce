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
final public class SmokeDriver {
    private final PvzceClient client;

    private final int captureFrame = Integer.getInteger("pvzce.captureFrame", -1);
    private final String capturePath = System.getProperty("pvzce.capturePath");
    /**
     * Capture a run of frames rather than one: {@code captureFrame}, then every this many frames.
     *
     * <p>One frame is enough for a screen - a menu looks the same on every frame it exists - and
     * not enough for anything that moves. A level's own clock is the server's, which runs at 60tps
     * in its own thread while the client draws at the display's rate, so "the third frame after
     * the blast" is not a number a person can work out in advance: it is 60 frames later on a
     * 60 Hz display and 120 on a 120 Hz one. An effect that lasts half a second lands somewhere in
     * a strip and nowhere in a guess. Files are written as {@code <capturePath>_<frame>.png} so
     * the frames keep their order on disk.
     *
     * <p>0 - the default - keeps {@link #captureFrame} a single shot.
     */
    private final int captureEvery = Integer.getInteger("pvzce.captureEvery", 0);
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
    /**
     * A schedule for the almanac: {@code frame:page[:entry},frame:page[:entry]}.
     *
     * <p>The book is the one screen whose interesting states are behind three navigations (index
     * -> index card -> entry, then the page arrows), and the click coordinates that would reach
     * them move with the window. A schedule walks it by frame instead, so one launch shoots the
     * index and one entry of each page - which is what the screenshot discipline asks for.
     *
     * <p>The schedule runs in {@link #beforeFrame()}, so a change and a {@code captureFrame} on
     * the same frame agree: the page is turned before the frame is drawn, and the capture reads
     * that same frame's finished buffer.
     */
    private final String smokeAlmanacShots = System.getProperty("pvzce.smokeAlmanacShots", "");
    private final java.util.Set<Long> almanacShotsDone = new java.util.HashSet<>();

    /**
     * A filmstrip across menu pages: {@code frame:page,frame:page}, where {@code page} is any key
     * {@code pvzce.smokeScreen} accepts.
     *
     * <p>For the same reason as {@link #smokeAlmanacShots}, one level up: a round of work that
     * touches the title screen, the shop and the packs page needs a screenshot of each, and only
     * one smoke launch is allowed per change. Paired with {@code captureEvery} this shoots all
     * three in one run - and a page that only draws correctly when it is the first screen of the
     * session is exactly what a second launch would have hidden.
     *
     * <p>Runs in {@link #beforeFrame()}, so the page named for a frame is the page that frame's
     * buffer shows.
     */
    private final String smokePages = System.getProperty("pvzce.smokePages", "");
    private final java.util.Set<Long> smokePagesDone = new java.util.HashSet<>();

    /**
     * {@code pvzce.smokeTray=<cell>@<frame>}: click a cell of the title screen's corner tray.
     *
     * <p>Exists because that tray is the one click target whose coordinates cannot be written down:
     * it is anchored to the player board's height, which comes from the font, and it scales with the
     * menu buttons, which come from the GUI height. So the click is aimed by asking the screen where
     * its cell is - the same method the hit test uses, which is also what makes this a check of the
     * two agreeing rather than of a number written here.
     */
    private final String smokeTray = System.getProperty("pvzce.smokeTray", "");
    private boolean smokeTrayClicked;

    /**
     * {@code pvzce.smokeCoins=<n>}: put coins in the world before anything is photographed.
     *
     * <p>The shop is a price list, and a fresh world has no money - so every screenshot of it used
     * to show a zero wallet, which is also the shape of the bug it had (a stale zero until a level
     * was started). This gives the world a balance through the same server call the console command
     * uses, so the frame shows what a player who can afford something sees.
     */
    private final int smokeCoins = Integer.getInteger("pvzce.smokeCoins", 0);
    private boolean smokeCoinsGranted;

    /** Saves the editor once, so a smoke run can verify the write round trip. */
    private final boolean smokeSave = Boolean.getBoolean("pvzce.smokeSave");
    /** Places presets on the editor board: {@code kind=id@x,y;kind=id@x,y}. */
    private final String smokePlace = System.getProperty("pvzce.smokePlace", "");
    private boolean smokeLevelRequested;
    /**
     * Development smoke hook: open a confirmation dialog once a level is running.
     *
     * <p>{@code -Ppvzce.smoke=pvzce.smokeConfirm=restart} puts the pause menu's restart question
     * on screen. It exists because that box has two parents - the pause menu and the seed
     * chooser - and neither is reachable from the title screen without driving several menus,
     * which a click-based smoke run does by guessing coordinates.
     */
    private final String smokeConfirm = System.getProperty("pvzce.smokeConfirm", "");
    private boolean smokeConfirmFired;
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
     * Development smoke hook: let the button go at this GUI point, {@code smokeClickFrame + this}
     * frames after {@code smokeClick} pressed it.
     *
     * <p><b>Why this hook has to exist.</b> {@code smokeClick} only sends a <em>press</em> - the real
     * release comes from the frame loop noticing that the button is no longer held, which a
     * synthetic click never causes. So before this, a screenshot run could prove that a widget saw
     * the press and nothing else, and every "acts on release" control (a window title bar's close
     * button, a dialog's confirm) looked dead to the harness while working for a human. That is the
     * same trap {@code pvzce.smokeSwipe} was added for: a hook that is green on a path the real
     * device never takes.
     *
     * <p>Opt-in through {@code pvzce.smokeRelease=<frames>}: off unless the run asks for it, so
     * every existing screenshot keeps the press-only behaviour it was written against.
     */
    private final Integer smokeReleaseAfter =
            Integer.getInteger("pvzce.smokeRelease");
    private boolean smokeReleaseDone;
    /**
     * Development smoke hook: a whole swipe - press, travel in steps, release - as
     * {@code fromX,fromY,toX,toY} in logical GUI coordinates.
     *
     * <p>{@code smokeDragTo} cannot stand in for this one: it calls the drag and release dispatch
     * directly, so it never reaches the gesture that decides whether a press is a tap or a scroll
     * (see {@code client.input.PointerGesture}). This hook drives the same three calls the frame loop
     * makes, which is the only way a screenshot can show that a swipe scrolled a list <em>and did not
     * press the row it started on</em>.
     */
    private final double[] smokeSwipe = parseSwipe(System.getProperty("pvzce.smokeSwipe", ""));
    private final int smokeSwipeFrame = Integer.getInteger("pvzce.smokeSwipeFrame", 90);
    private boolean smokeSwipeDone;
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
     * {@code pvzce.smokeTouchSource=true}: send {@code smokeClick} / {@code smokeClickLabel} /
     * {@code smokeDragTo} through the touch translator instead of the mouse dispatch.
     *
     * <p>The two are not the same path: the mouse hooks deliver a click and a drag directly, while a
     * touch goes down / moves / up through {@code TouchTranslator} and the gesture that decides
     * tap-versus-scroll. A screenshot run that wants to show "a finger does this" has to use this.
     */
    private final boolean smokeTouchSource = Boolean.getBoolean("pvzce.smokeTouchSource");
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
        if (!openNamedScreen(System.getProperty("pvzce.smokeScreen", ""))) {
            client.setScreenReplacing(new TitleScreen(client));
        }
    }

    /**
     * Opens the screen a {@code pvzce.smokeScreen}-style key names, and answers whether it knew it.
     *
     * <p>A method rather than the body of {@link #applyInitialScreen} because the filmstrip hook
     * ({@code pvzce.smokePages}) walks the same keys: one launch is allowed per change (see
     * {@code docs/冒烟与截图指南.md}), so a round that touches three menus has to shoot all three
     * in one run - and "the page the key names" must mean exactly the same thing in both.
     */
    boolean openNamedScreen(String smokeScreen) {
        if ("mods".equals(smokeScreen)) {
            client.setScreenReplacing(new com.pvzce.client.gui.mods.ModsScreen(client));
        } else if ("settings".equals(smokeScreen)) {
            client.setScreenReplacing(new com.pvzce.client.gui.screens.SettingsScreen(client));
        } else if ("shop".equals(smokeScreen)) {
            // The shop is reached from the title screen in play, but a smoke run that had to click
            // its way there would be a smoke run that broke the next time the menu's layout moved.
            client.setScreenReplacing(new com.pvzce.client.gui.screens.ShopScreen(client));
        } else if ("packs".equals(smokeScreen)) {
            // Same reason as the shop: the page is reached from the title screen in play, and a
            // smoke run that had to click its way there would break the next time the menu moved.
            client.setScreenReplacing(new com.pvzce.client.gui.screens.PackScreen(client));
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
        } else if ("almanac".equals(smokeScreen)) {
            // The index first; the hooks below walk into a page, which is four states deep
            // (index -> plants -> entry) and no screenshot run could click its way there.
            com.pvzce.client.gui.screens.AlmanacScreen almanac =
                    new com.pvzce.client.gui.screens.AlmanacScreen(client);
            client.setScreenReplacing(almanac);
            String page = System.getProperty("pvzce.smokeAlmanacPage", "");
            if (!page.isBlank()) {
                almanac.show(com.pvzce.client.gui.almanac.AlmanacEntries.Page.valueOf(
                        page.toUpperCase(java.util.Locale.ROOT)));
                String entry = System.getProperty("pvzce.smokeAlmanacEntry", "");
                if (!entry.isBlank()) {
                    almanac.selectEntry(Integer.parseInt(entry));
                }
            }
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
            return false;
        }
        return true;
    }

    /**
     * The hooks that must run before this frame's screen tick: getting into a level, running
     * commands, and reaching the editor (which is four screens deep and no run could walk to).
     */
    /**
     * {@code -Dpvzce.traceFrames=true}: print the average cost of each part of a frame, once a
     * second.
     *
     * <p>Added because "this level is slow" is otherwise a guess: the first measurement it produced
     * was an endless level's wave meter walking four thousand waves a frame, which was 46ms of a
     * 55ms frame and invisible in any code review.
     */
    private final boolean traceFrames = Boolean.getBoolean("pvzce.traceFrames");
    private long frameNanos;
    private long tickNanos;
    private long renderNanos;
    private int frameSamples;

    /** Times one part of the frame; see {@link #traceFrames}. */
    public void addTickNanos(long nanos) {
        tickNanos += nanos;
    }

    /** Times one part of the frame; see {@link #traceFrames}. */
    void addRenderNanos(long nanos) {
        renderNanos += nanos;
    }

    /** Reports and resets the frame budget, once per second. */
    void sampleFrame() {
        if (!traceFrames) {
            return;
        }
        frameSamples++;
        long now = System.nanoTime();
        if (frameNanos == 0L) {
            frameNanos = now;
            return;
        }
        if (now - frameNanos < 1_000_000_000L) {
            return;
        }
        double seconds = (now - frameNanos) / 1_000_000_000D;
        System.out.println(String.format(java.util.Locale.ROOT,
                "[FRAME] %.1f fps | tick %.2f ms | render %.2f ms | other %.2f ms | frames %d",
                frameSamples / seconds, tickNanos / 1e6 / frameSamples,
                renderNanos / 1e6 / frameSamples,
                (seconds * 1000D - (tickNanos + renderNanos) / 1e6) / frameSamples,
                frameSamples));
        frameNanos = now;
        tickNanos = 0L;
        renderNanos = 0L;
        frameSamples = 0;
    }

    void beforeFrame() {
        long clientTick = client.clientTick();
        applySmokeCoins(clientTick);
        applyTrayClick();
        // Before the render, not after: the capture hook below runs after the buffers were
        // swapped, so a page turned in afterFrame would be one frame late in the PNG.
        applyAlmanacShots(client.currentScreen(), clientTick);
        applySmokePages(clientTick);
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
        if (!smokeConfirm.isBlank() && !smokeConfirmFired && clientTick > 120
                && client.currentScreen() instanceof com.pvzce.client.gui.screens.InGameScreen) {
            smokeConfirmFired = true;
            com.pvzce.client.gui.screens.ConfirmDialog box = "sun".equals(smokeConfirm)
                    ? com.pvzce.client.gui.screens.ConfirmDialog.startWithoutSun(client, () -> {
                    }, null)
                    : com.pvzce.client.gui.screens.ConfirmDialog.restart(client, () -> {
                    }, null);
            client.currentScreen().showDialog(box);
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
                        if (smokeTouchSource) {
                            client.deliverGuiTouch(cx, cy, cx, cy);
                        } else {
                            client.deliverGuiClick(cx, cy, 0);
                        }
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
            if (smokeTouchSource) {
                client.deliverGuiTouch(gui[0], gui[1], gui[0], gui[1]);
            } else {
                client.deliverRawClick(rawX, rawY, 0);
            }
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
            if (smokeTouchSource && smokeClickAt != null) {
                System.out.println("[SMOKE] touching from gui=" + smokeClickAt[0] + "," + smokeClickAt[1]
                        + " to gui=" + gui[0] + "," + gui[1]);
                client.deliverGuiTouch(smokeClickAt[0], smokeClickAt[1], gui[0], gui[1]);
            } else {
                client.deliverRawDrag(rawX, rawY, 0);
                client.deliverRawRelease(rawX, rawY, 0);
            }
        }
        // Not together with smokeDragTo: that hook releases on its own, and a second release would
        // reach the layers below as a button going up twice.
        if (smokeReleaseAfter != null && !smokeReleaseDone && smokeClickAt != null
                && smokeDragTo == null
                && smokeClicksSent > 0 && clientTick >= smokeClickFrame + Math.max(0, smokeReleaseAfter)) {
            smokeReleaseDone = true;
            double[] gui = smokeClickAt;
            double rawX = gui[0] * client.window().width() / (double) Math.max(1, client.guiWidth());
            double rawY = client.window().height()
                    - gui[1] * client.window().height() / (double) Math.max(1, client.guiHeight());
            System.out.println("[SMOKE] release gui=" + gui[0] + "," + gui[1]);
            client.deliverRawRelease(rawX, rawY, 0);
        }
        if (smokeSwipe != null && !smokeSwipeDone && clientTick == smokeSwipeFrame) {
            smokeSwipeDone = true;
            System.out.println("[SMOKE] swipe gui=" + smokeSwipe[0] + "," + smokeSwipe[1]
                    + " -> " + smokeSwipe[2] + "," + smokeSwipe[3]);
            client.deliverGuiSwipe(smokeSwipe[0], smokeSwipe[1], smokeSwipe[2], smokeSwipe[3]);
        }
        if (smokeFrames > 0 && clientTick == smokeFrames) {
            System.out.println("[SMOKE] frame " + smokeFrames + " rendered, screen="
                    + screen.getClass().getSimpleName());
            return true;
        }
        if (capturePath != null && clientTick >= captureFrame && captureFrame >= 0) {
            if (captureEvery <= 0) {
                if (clientTick == captureFrame) {
                    capture(capturePath);
                }
            } else if ((clientTick - captureFrame) % captureEvery == 0) {
                capture(stripPath(capturePath, clientTick));
            }
        }
        return false;
    }

    /**
     * Walks the almanac to the state a schedule item asks for, on its own frame.
     *
     * <p>Applied before {@link #capturePath}'s timer, so a capture frame in the same item sees the
     * page it just turned to rather than the one before it.
     */
    private void applyAlmanacShots(com.pvzce.client.gui.Screen screen, long clientTick) {
        if (smokeAlmanacShots.isBlank()
                || !(screen instanceof com.pvzce.client.gui.screens.AlmanacScreen almanac)) {
            return;
        }
        // This hook runs before the client initializes the screen for the frame, and a screen is
        // initialized lazily - so a page asked for here would be thrown away a moment later by
        // init() rebuilding its catalogues. Initialize first, then steer.
        screen.initIfNeeded();
        for (String item : smokeAlmanacShots.split(",")) {
            String[] parts = item.trim().split(":");
            if (parts.length < 2) {
                continue;
            }
            long frame;
            try {
                frame = Long.parseLong(parts[0].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (clientTick != frame || !almanacShotsDone.add(frame)) {
                continue;
            }

            String page = parts[1].trim();
            // "index" is a page name like any other here: it is what no-page means.
            if ("index".equalsIgnoreCase(page)) {
                almanac.showIndex();
            } else {
                almanac.show(com.pvzce.client.gui.almanac.AlmanacEntries.Page.valueOf(
                        page.toUpperCase(java.util.Locale.ROOT)));
                if (parts.length > 2) {
                    almanac.selectEntry(Integer.parseInt(parts[2].trim()));
                }
            }
            System.out.println("[SMOKE] almanac frame " + frame + " -> " + item.trim()
                    + " (page=" + almanac.pageIndex() + " entry=" + almanac.currentEntry() + ")");
        }
    }

    /**
     * {@code pvzce.smokePages}: walk a schedule of menu pages, one screen per named frame.
     *
     * <p>The same shape as {@link #applyAlmanacShots} and for the same reason - a run that has to
     * click its way to a page breaks the next time a menu moves - but across screens rather than
     * within one. An unknown key is reported rather than ignored: a silently skipped page is a
     * screenshot that looks like the hook did nothing.
     */
    private void applySmokePages(long clientTick) {
        if (smokePages.isBlank()) {
            return;
        }
        for (String item : smokePages.split(",")) {
            String[] parts = item.trim().split(":");
            if (parts.length < 2) {
                continue;
            }
            long frame;
            try {
                frame = Long.parseLong(parts[0].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (clientTick != frame || !smokePagesDone.add(frame)) {
                continue;
            }
            String page = parts[1].trim();
            if (openNamedScreen(page)) {
                System.out.println("[SMOKE] frame " + frame + " -> page "
                        + page + " (" + client.currentScreen().getClass().getSimpleName() + ")");
            } else {
                System.out.println("[SMOKE] frame " + frame + " -> unknown page '" + page + "'");
            }
        }
    }

    /**
     * {@code pvzce.smokeCoins}: put coins in the menus' world, once the world is chosen.
     *
     * <p>Goes through the console command ({@code /profile coins <n>}) rather than reaching into the
     * server, because a single-player client holds no server reference - the command path is the
     * only one that exists. It waits for the level list, which is what makes the server pick a
     * world: paying before that would pay whichever world the menu happened to default to.
     */
    private void applySmokeCoins(long clientTick) {
        if (smokeCoins <= 0 || smokeCoinsGranted || clientTick < 20 || client.levelList().isEmpty()) {
            return;
        }
        smokeCoinsGranted = true;
        client.connection().send(new CommandC2S("profile coins " + smokeCoins));
        System.out.println("[SMOKE] asking for " + smokeCoins + " coins in world '"
                + client.currentWorld() + "'");
    }

    /**
     * {@code pvzce.smokeTray}: aims a real click at a corner-tray cell.
     *
     * <p>Reported rather than silent: "the click did nothing" and "the click missed" look identical
     * in a screenshot, and the whole point of this hook is the coordinate.
     */
    private void applyTrayClick() {
        if (smokeTray.isBlank() || smokeTrayClicked) {
            return;
        }
        String[] parts = smokeTray.split("@");
        int cell;
        long frame;
        try {
            cell = Integer.parseInt(parts[0].trim());
            frame = parts.length > 1 ? Long.parseLong(parts[1].trim()) : 40L;
        } catch (NumberFormatException e) {
            System.out.println("[SMOKE] smokeTray wants <cell>@<frame>, got '" + smokeTray + "'");
            smokeTrayClicked = true;
            return;
        }
        if (client.clientTick() < frame) {
            return;
        }
        smokeTrayClicked = true;
        if (!(client.currentScreen() instanceof TitleScreen menu)) {
            System.out.println("[SMOKE] smokeTray: the title screen is not up ("
                    + client.currentScreen().getClass().getSimpleName() + ")");
            return;
        }
        TitleScreen.TrayCell target = menu.trayCell(cell);
        System.out.println("[SMOKE] tray cell " + cell + " at gui=" + target.centerX() + ","
                + target.centerY());
        client.deliverGuiClick(target.centerX(), target.centerY(), 0);
    }

    /** {@code /tmp/x.png} at frame 240 becomes {@code /tmp/x_240.png}. */
    private static String stripPath(String path, long frame) {
        int dot = path.lastIndexOf('.');
        String stem = dot < 0 ? path : path.substring(0, dot);
        String extension = dot < 0 ? "" : path.substring(dot);
        return stem + "_" + frame + extension;
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
    /** Four numbers, for the one hook that needs a start and an end. */
    private static double[] parseSwipe(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length != 4) {
            return null;
        }
        try {
            double[] parsed = new double[4];
            for (int i = 0; i < 4; i++) {
                parsed[i] = Double.parseDouble(parts[i].trim());
            }
            return parsed;
        } catch (NumberFormatException e) {
            return null;
        }
    }

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
