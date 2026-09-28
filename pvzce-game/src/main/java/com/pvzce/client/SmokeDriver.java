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
    /**
     * Several editor pages in one launch: {@code pvzce.smokeEditorPages=<frame>:<page>,…}.
     *
     * <p>The editor's pages are the only place three of the four content palettes live, and one
     * launch is allowed per change - so "look at the plant palette and the zombie palette and the
     * card list" has to be one run. Same shape as {@code pvzce.smokeAlmanacShots}, and for the
     * same reason: the page has to be switched from {@code beforeFrame} so that frame's PNG is
     * the page it names.
     */
    private final String smokeEditorPages = System.getProperty("pvzce.smokeEditorPages", "");
    private final java.util.Set<Long> smokeEditorPagesDone = new java.util.HashSet<>();
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

    /**
     * {@code pvzce.smokeCollection=<collection id>}: open that collection's screen.
     *
     * <p>A collection is reached by picking its row in the level list, which is a click no
     * screenshot run can make - the row it wants is not necessarily on the page the run opens on,
     * and the list arrives a frame after the screen does. So the run stands up the level list
     * (see {@code openNamedScreen}) and then pushes the collection's screen once its rows exist.
     */
    private final String smokeCollection = System.getProperty("pvzce.smokeCollection", "");
    /**
     * The collection waiting to be opened, or empty.
     *
     * <p>A field rather than a flag, because the hook is reachable twice: once from
     * {@code pvzce.smokeCollection} at startup and once from a {@code collection:<id>} entry in
     * {@code pvzce.smokePages}. Each sets it, and {@link #applySmokeCollection} clears it when the
     * screen is up - so one run can film a level, then the list, then a box's own page.
     */
    private String pendingCollection = "";
    /** True once the property's target has been handed to {@link #pendingCollection}. */
    private boolean smokeCollectionOpenedOnce;

    /**
     * {@code pvzce.smokeStartWaves=<frame>}: press 开始 on the preparation phase's own clock.
     *
     * <p>A level that opens in its build phase waits for the player, and no other hook can answer
     * it: {@code smokeClickLabel} only reaches buttons inside a dialog, and the 开始 button is a
     * widget of the level's own HUD whose rectangle depends on the window size. Without this, any
     * screenshot of a level that <em>starts</em> when the waves do - which is every rhythm level,
     * where the chart's anchor is that very moment - shows the build phase and nothing else.
     *
     * <p>Fires on the first frame at or after the number that finds the level preparing, rather
     * than exactly on it: how long the level takes to arrive is the server's business, and a hook
     * that missed its frame would be a silent no-op.
     */
    private final int smokeStartWaves = Integer.getInteger("pvzce.smokeStartWaves", 0);
    private boolean smokeStartWavesSent;

    /**
     * {@code pvzce.smokeAutoPlay}: play the chart, on the chart's own clock.
     *
     * <p>What {@code smokeKeys} cannot do. A key script presses on frames, and a frame is not a tick:
     * the client runs at whatever rate the machine manages, so a press meant for a beat lands tens of
     * ticks away - and worse than random, because a note is consumed by the <em>first</em> press
     * inside its window, so a script that presses continuously scores FAIR on every note and never a
     * PERFECT. That makes every picture of this mode's rewards unreachable: no streak, no ×N beside
     * the word, no jalapenos, and a report page whose numbers are whatever mashing produces.
     *
     * <p>So this plays as a player with perfect timing would: each lane is pressed when its next
     * unplayed note is inside the perfect window, read from the level's own chart and the anchor the
     * server sent. It is a development hook and only that - it is not a bot for a real run - which is
     * why it lives here rather than behind a key: it presses the same keys through the same queue.
     */
    private final boolean smokeAutoPlay = Boolean.getBoolean("pvzce.smokeAutoPlay");
    /** Lane key -> the last note this hook pressed, so one note is answered once. */
    private final java.util.Map<String, Integer> autoPlayPressed = new java.util.HashMap<>();

    private void applySmokeAutoPlay() {
        if (!smokeAutoPlay || !(client.currentScreen()
                instanceof com.pvzce.client.gui.screens.InGameScreen) || client.level() == null) {
            return;
        }
        com.pvzce.api.content.RhythmChartData chart = client.level()
                .mechanicData(com.pvzce.common.PvzceIds.MECHANIC_RHYTHM,
                        com.pvzce.api.content.RhythmChartData.class);
        com.pvzce.common.level.mechanic.RhythmMechanic.Status status = client.level()
                .mechanicStateOrNull(com.pvzce.common.PvzceIds.MECHANIC_RHYTHM,
                        com.pvzce.common.level.mechanic.RhythmMechanic.Status.class);
        if (chart == null || status == null || status.tick() < 0) {
            // No chart, or a build phase: nothing is due and no key is a note.
            return;
        }
        double now = client.level().smoothLevelTicks() - status.tick();
        for (com.pvzce.api.content.RhythmChartData.Lane lane : chart.lanes()) {
            String key = com.pvzce.common.level.mechanic.RhythmMechanic.laneKey(lane.kind(),
                    lane.index());
            int last = autoPlayPressed.getOrDefault(key, Integer.MIN_VALUE);
            for (int note : chart.ticksOf(lane)) {
                if (note <= last) {
                    continue;
                }
                double delta = note - now;
                if (delta > chart.perfectTicks()) {
                    // Still on its way down: the notes ascend, so nothing later is due either.
                    break;
                }
                if (-delta > chart.perfectTicks()) {
                    // Gone by while this hook was not looking; note it and move to the next one.
                    autoPlayPressed.put(key, note);
                    continue;
                }
                autoPlayPressed.put(key, note);
                com.pvzce.client.input.KeyBindings.Action action =
                        com.pvzce.client.input.KeyBindings.Action.forLane(lane.kind().json(),
                                lane.index());
                if (action != null) {
                    client.window().injectKey(client.keyBindings().code(action));
                }
                break;
            }
        }
    }

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
     * Development smoke hook: hold the mouse button from frame {@code hold} to
     * {@code hold + holdFrames}, over the <b>frame loop</b> rather than over the dispatch.
     *
     * <p><b>Why the other hooks are not enough.</b> {@code smokeClick} and {@code smokeRelease} call
     * {@code deliverRawClick} / {@code deliverRawRelease}, which jump straight to
     * {@code dispatchMouseClicked} / {@code dispatchMouseReleased}. Everything the frame loop does
     * around them is skipped: the gesture's press branch, the "is the button still down" poll that
     * decides click-versus-drag, and every early return on the way. A control that works under a
     * synthetic click and not under a real one is exactly the failure this hook exists to catch -
     * the same lesson as {@code pvzce.smokeSwipe} (see 踩坑清单 116).
     *
     * <p>{@code pvzce.smokeHold=<x,yFromTop>} plus {@code pvzce.smokeHoldFrame=<n>} (default 60) and
     * {@code pvzce.smokeHoldFrames=<n>} (default 3): the pointer is put there (warped, and recorded
     * every frame because a Wayland compositor is free to ignore the warp), then the button is
     * pressed and released through the polled state across those frames.
     *
     * <p><b>Y counts down from the top</b>, not up from the bottom like every other smoke hook:
     * that is the space the pointer callback reports and the space the window title bar hit-tests
     * in, so a "hold the close button" run can be written from the screenshot instead of from its
     * mirror image.
     */
    private final double[] smokeHoldAt = parsePoint(System.getProperty("pvzce.smokeHold", ""));
    private final int smokeHoldFrame = Integer.getInteger("pvzce.smokeHoldFrame", 60);
    private final int smokeHoldFrames = Math.max(1, Integer.getInteger("pvzce.smokeHoldFrames", 3));
    private boolean smokeHoldWarped;
    private boolean smokeHoldPressed;
    private boolean smokeHoldReleased;
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
    /** {@code pvzce.smokeHoverCell=<x>,<y>}: the same, for a board cell rather than a GUI point. */
    private final int[] smokeHoverCellAt = parseIntPair(System.getProperty("pvzce.smokeHoverCell", ""));
    /**
     * {@code pvzce.smokeClickCell=<x>,<y>[,...]}: click board cells rather than GUI points.
     *
     * <p>The sibling {@code smokeHoverCell} always implied: a cell's screen position is the
     * camera's answer, and a script that hard-codes those pixels breaks on any other window size
     * (and silently - a click that misses looks like a control that does not work). It is what a
     * two-click interaction needs, because the first click and the second are on different cells
     * and both have to be found the same way: the cob cannon's "click the plant, then click where
     * the cob should land" cannot be photographed without it.
     *
     * <p>A list, because those two clicks are the point: each entry is one press, {@code
     * smokeClickFrame + k * smokeClickPeriod} frames apart, the same clock {@code smokeClick}
     * repeats on.
     */
    private final java.util.List<int[]> smokeClickCells = parseCells(
            System.getProperty("pvzce.smokeClickCell", ""));
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
    private int smokeClickCellsSent;
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
    /**
     * Commands to run <em>before</em> the automatic level request, on whatever screen is up.
     *
     * <p>{@link #smokeCommands} fires once a level is running, which is too late for anything a
     * level is built from: the world's profile decides which cards the level offers, whether the
     * player's rake is laid down, and what the level list looks like, and all of that is read at
     * level creation. A screenshot of "the board a world that owns the rake gets" therefore needs
     * the world changed first, and this is that hook. Send order is the guarantee: a command goes
     * through the server's command queue, which is drained at the top of the server frame, and the
     * level request travels the packet connection, drained after it - in the same frame.
     *
     * <p>Same {@code +}-for-space rule as {@link #smokeCommands}.
     */
    private final String smokeSetup = System.getProperty("pvzce.smokeSetup", "");
    private boolean smokeSetupSent;
    /**
     * Keys to press, as {@code pvzce.smokeKeys=<frame>:<GLFW code>,…}.
     *
     * <p>Queued into the window's own press queue rather than dispatched at the handler, so what a
     * run proves is the whole input path - the bound-action table, the overlay, the screen - and not
     * just the last method in it.
     */
    private final String smokeKeys = System.getProperty("pvzce.smokeKeys", "");
    private final java.util.Set<Long> smokeKeysDone = new java.util.HashSet<>();
    /**
     * Text to type, as {@code pvzce.smokeType=<frame>:<text>,…}.
     *
     * <p>One character per frame, which is what a person does and what an {@code EditBox} expects:
     * it appends per char event and has no notion of a pasted string.
     */
    private final String smokeType = System.getProperty("pvzce.smokeType", "");
    private final java.util.Set<Long> smokeTypeDone = new java.util.HashSet<>();
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
            // Not `new TitleScreen(...)`: a fresh install opens the first-run page, and the smoke
            // hook must be able to reach the same screen a player would (see
            // `PvzceClient.openFirstScreen`).
            client.openFirstScreen();
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
        } else if ("keybinds".equals(smokeScreen)) {
            client.setScreenReplacing(new com.pvzce.client.gui.screens.KeybindScreen(client));
        } else if ("onboarding".equals(smokeScreen)) {
            client.setScreenReplacing(new com.pvzce.client.gui.screens.OnboardingScreen(client));
        } else if ("difficulty".equals(smokeScreen)) {
            client.setScreenReplacing(new com.pvzce.client.gui.screens.DifficultyScreen(client));
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
        } else if (smokeScreen.startsWith("collection:")) {
            // One box's own page, reached the way the level list reaches it: stand the list up,
            // then push the box once its row has arrived (see `applySmokeCollection`).
            pendingCollection = smokeScreen.substring("collection:".length()).trim();
            client.setCurrentWorld(System.getProperty("pvzce.smokeWorld", "world"));
            client.setScreenReplacing(new LevelSelectScreen(client));
            client.connection().send(new RequestLevelListC2S(client.currentWorld()));
        } else if ("levels".equals(smokeScreen) || !smokeCollection.isBlank()) {
            // The level list is where "new level" and "edit level" now live, and it needs
            // a level list from the server to show anything. The world has to be named:
            // the list is per world, and an unnamed one comes back empty.
            //
            // `pvzce.smokeCollection` rides the same branch: a collection's screen is opened from
            // a row of this list, and it draws its members out of the list itself, so the run has
            // to stand the list up first either way. The push happens in `applySmokeCollection`,
            // a frame or two later, once the rows have arrived.
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
            //
            // One of each kind of grant, in the page's own priority order: that is the shape the
            // page exists for ("what did I get", with a sentence per thing), and a single card
            // would not show the list, the descriptions or the demoted coin line.
            client.setScreenReplacing(new com.pvzce.client.gui.screens.AwardScreen(client,
                    new LevelRewardS2C("pvzce:yard/adventure/1_1", 12, 100, 462,
                            LevelRewardS2C.grantsOf("pvzce:sunflower", "pvzce:auto_collect",
                                    "pvzce:diamond", 2),
                            Float.NaN, Float.NaN, 0, 0)));
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

    /**
     * Presses and releases the button across frames, so the frame loop's own input path runs.
     *
     * <p>The press is queued for {@code pollInput}'s button branch and reported as "still down"
     * until the release frame, which is what a real hold looks like from inside the loop.
     */
    private void applySmokeHold(long clientTick) {
        if (smokeHoldAt == null) {
            return;
        }
        // Warped on the release frame at the earliest, never before the second frame: the window is
        // resized by the compositor right after it is shown (2560x1440 asked for, 2560x1408 given on
        // the machine this was written on), and a warp computed from the pre-clamp size lands off the
        // top edge - which silently turns "hold the close button" into "hold nothing".
        if (!smokeHoldWarped && clientTick >= Math.max(2, smokeHoldFrame - 2)) {
            smokeHoldWarped = true;
            double rawX = smokeHoldAt[0] * client.window().width()
                    / (double) Math.max(1, client.guiWidth());
            double rawY = smokeHoldAt[1] * client.window().height()
                    / (double) Math.max(1, client.guiHeight());
            System.out.println("[SMOKE] hold at x=" + smokeHoldAt[0] + " yFromTop=" + smokeHoldAt[1]
                    + " raw=" + rawX + "," + rawY);
            client.window().warpCursor(rawX, rawY);
        }
        if (clientTick >= smokeHoldFrame && !smokeHoldReleased) {
            // Warping is only a request - on Wayland this compositor ignores glfwSetCursorPos
            // outright, so the pointer stays wherever it was and a "hold the close button" run
            // silently holds some other pixel. Recording the position is what the game actually
            // reads (the cursor callback writes the same two fields), so re-recording it every
            // frame until the release keeps the synthetic click where the run aimed it.
            double rawX = smokeHoldAt[0] * client.window().width()
                    / (double) Math.max(1, client.guiWidth());
            double rawY = smokeHoldAt[1] * client.window().height()
                    / (double) Math.max(1, client.guiHeight());
            client.window().setPointerPosition(rawX, rawY);
        }
        if (!smokeHoldPressed && clientTick >= smokeHoldFrame) {
            smokeHoldPressed = true;
            client.window().pressButtonForSmoke(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
        }
        if (smokeHoldPressed && !smokeHoldReleased
                && clientTick >= smokeHoldFrame + smokeHoldFrames) {
            smokeHoldReleased = true;
            client.window().pressButtonForSmoke(null);
            System.out.println("[SMOKE] hold released at frame " + clientTick);
        }
    }

    /**
     * Sends a {@code |}-separated list of console commands, one packet each.
     *
     * <p>One reader for both command hooks, so the {@code +}-for-space rule and the
     * drop-the-leading-slash rule cannot drift between them.
     */
    private void sendCommands(String commands) {
        for (String command : commands.split("\\|")) {
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

    void beforeFrame() {
        if (pendingCollection.isEmpty() && !smokeCollection.isBlank()
                && !smokeCollectionOpenedOnce) {
            smokeCollectionOpenedOnce = true;
            pendingCollection = smokeCollection;
        }
        long clientTick = client.clientTick();
        applySmokeHold(clientTick);
        applySmokeCoins(clientTick);
        applySmokeCollection(clientTick);
        applySmokeStartWaves(clientTick);
        applySmokeAutoPlay();
        applyTrayClick();
        // Before the render, not after: the capture hook below runs after the buffers were
        // swapped, so a page turned in afterFrame would be one frame late in the PNG.
        applyAlmanacShots(client.currentScreen(), clientTick);
        applySmokePages(clientTick);
        applySmokeEditorPages(clientTick);
        applySmokeKeys(clientTick);
        applySmokeType(clientTick);
        // Before the level request and never after: everything this hook exists to change (which
        // cards the world owns, whether it has a rake) is read when the level is built.
        if (!smokeSetup.isBlank() && !smokeSetupSent && clientTick > 2) {
            smokeSetupSent = true;
            sendCommands(smokeSetup);
        }
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
            sendCommands(smokeCommands);
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
     * Whether the frame-numbered hooks may fire yet.
     *
     * <p>A run that asks for a level ({@code pvzce.smokeLevel}) counts frames <b>from the level</b>:
     * "frame 150" means 150 frames into the game rather than into the loading screen, which is what
     * a script author writing "press T, then type" means - and the loading screen would eat the
     * typing, since it has no text box to put it in.
     *
     * <p>A run with no level counts <b>from launch</b>, because there is no level to count from: a
     * menu page driven by keys (the first-run page, the key binding page) never enters a level, and
     * gating those runs on "a level is up" would arm the hooks never.
     */
    private boolean hooksArmed() {
        if (smokeLevel.isBlank()) {
            return true;
        }
        return client.currentScreen() instanceof com.pvzce.client.gui.screens.InGameScreen;
    }

    /**
     * {@code pvzce.smokeKeys=<frame>:<code>,…}: queue key presses on the frames named.
     *
     * <p>A frame may carry more than one press, separated by {@code +} - which is how a run types a
     * word into an open text box (the box consumes the callbacks, so a burst is the same as a
     * person typing fast).
     */
    private void applySmokeKeys(long clientTick) {
        if (smokeKeys.isBlank() || !hooksArmed()) {
            return;
        }
        for (String item : smokeKeys.split(",")) {
            String[] parts = item.trim().split(":", 2);
            if (parts.length < 2) {
                continue;
            }
            long frame;
            try {
                frame = Long.parseLong(parts[0].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (clientTick != frame || !smokeKeysDone.add(frame)) {
                continue;
            }
            for (String code : parts[1].trim().split("\\+")) {
                try {
                    client.window().injectKey(Integer.parseInt(code.trim()));
                } catch (NumberFormatException e) {
                    System.out.println("[SMOKE] not a key code: " + code);
                }
            }
        }
    }

    /** {@code pvzce.smokeType=<frame>:<text>,…}: one character per frame from the frame named. */
    private void applySmokeType(long clientTick) {
        if (smokeType.isBlank() || !hooksArmed()) {
            return;
        }
        for (String item : smokeType.split(",")) {
            String[] parts = item.trim().split(":", 2);
            if (parts.length < 2) {
                continue;
            }
            long frame;
            try {
                frame = Long.parseLong(parts[0].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (clientTick != frame || !smokeTypeDone.add(frame)) {
                continue;
            }
            String text = parts[1];
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '~') {
                    // A space, because the property itself is whitespace-separated.
                    ch = ' ';
                }
                client.window().injectChar(ch);
            }
        }
    }

    /**
     * {@code pvzce.smokeEditorPages=<frame>:<page>,…}: turn the editor to a page on a frame.
     *
     * <p>Runs in {@code beforeFrame} like {@code smokePages}, so the frame it names is the frame
     * the PNG shows. The page has to be re-initialised after the switch or the outgoing page's
     * widgets are the ones drawn.
     */
    private void applySmokeEditorPages(long clientTick) {
        if (smokeEditorPages.isBlank() || !(client.currentScreen() instanceof EditorScreen editor)) {
            return;
        }
        for (String item : smokeEditorPages.split(",")) {
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
            if (clientTick != frame || !smokeEditorPagesDone.add(frame)) {
                continue;
            }
            String page = parts[1].trim();
            editor.showPageForSmoke(page);
            System.out.println("[SMOKE] editor frame " + frame + " -> page " + page);
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
        // A list of cells rather than GUI points: the camera is asked where each one is, so the
        // same script works at any resolution and on any board size (see smokeClickCell).
        if (!smokeClickCells.isEmpty() && smokeClickCellsSent < smokeClickCells.size()
                && clientTick >= smokeClickFrame
                && (clientTick - smokeClickFrame) % Math.max(1, smokeClickPeriod) == 0) {
            int[] cell = smokeClickCells.get(smokeClickCellsSent);
            smokeClickCellsSent++;
            smokeClickDone = true;
            if (client.level() != null) {
                com.pvzce.client.renderer.PvzceCamera camera = client.camera();
                double rawX = camera.screenX(cell[0] + 0.5F);
                double rawY = client.window().height() - camera.screenY(cell[1] + 0.5F);
                if (traceInput) {
                    System.out.println("[SMOKE] click cell=" + cell[0] + "," + cell[1]
                            + " raw=" + rawX + "," + rawY);
                }
                client.deliverRawClick(rawX, rawY, 0);
            }
        }
        if (smokeHoverCellAt != null && clientTick >= smokeHoverFrame) {
            // A board cell by its grid coordinates: the camera is the only thing that knows where a
            // cell is on screen, and guessing those pixels is how a hover hook ends up pointing at
            // the wrong row and proving nothing.
            //
            // `screenY` answers in the board's own space - bottom-up framebuffer pixels, the same
            // space `worldY` and `inBoard` flip out of - while the pointer it is fed to is top-down.
            // Without the flip the hover lands half a board lower than it says: on a 5x9 lawn
            // `smokeHoverCell=4,2` put the cursor over row 3, and a tool hotkey aimed at the plant
            // in row 2 was refused for an empty cell. (The board is vertically centred, so the two
            // mistakes cancel *exactly* at the middle row - which is why a row-2 probe looked
            // right in a screenshot while the click was one row off.)
            com.pvzce.client.renderer.PvzceCamera camera = client.camera();
            double rawX = camera.screenX(smokeHoverCellAt[0] + 0.5F);
            double rawY = client.window().height() - camera.screenY(smokeHoverCellAt[1] + 0.5F);
            client.window().warpCursor(rawX, rawY);
        } else if (smokeHoverAt != null && clientTick >= smokeHoverFrame) {
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
            // Split once: a page key may carry an argument of its own, as
            // `collection:pvzce:collections/day_lawn` does, and splitting on every colon turned
            // that into the bare word "collection" - which was reported as an unknown page and
            // looked exactly like a hook that had not fired.
            String[] parts = item.trim().split(":", 2);
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
    /**
     * {@code pvzce.smokeCollection=<collection id>}: open that collection's screen.
     *
     * <p>A frame or two after the level list arrives, because the screen is built out of it: it
     * resolves the collection's row and every member's row by id, exactly as a click would, so a
     * run that pushed it before the list existed would screenshot an empty page and look like the
     * feature was broken.
     */
    private void applySmokeStartWaves(long clientTick) {
        if (smokeStartWaves <= 0 || smokeStartWavesSent || clientTick < smokeStartWaves) {
            return;
        }
        if (!(client.currentScreen() instanceof com.pvzce.client.gui.screens.InGameScreen)
                || client.level() == null || !client.level().preparing()) {
            return;
        }
        smokeStartWavesSent = true;
        client.connection().send(new com.pvzce.common.network.packet.StartWavesC2S());
        System.out.println("[SMOKE] frame " + clientTick + " pressed 开始 (waves start)");
    }

    private void applySmokeCollection(long clientTick) {
        if (pendingCollection.isBlank() || clientTick < 3
                || !(client.currentScreen() instanceof com.pvzce.client.gui.screens.LevelSelectScreen)) {
            return;
        }
        String id = pendingCollection;
        boolean listed = false;
        for (com.pvzce.common.network.packet.LevelListS2C.LevelInfo info : client.levelList()) {
            if (info.isCollection() && info.id().equals(id)) {
                listed = true;
                break;
            }
        }
        if (!listed) {
            return;
        }
        pendingCollection = "";
        client.openScreen(new com.pvzce.client.gui.screens.LevelCollectionScreen(client, id));
        System.out.println("[SMOKE] opened collection " + id);
    }

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
        // The reader is shared with the F2 key: the flip from the framebuffer's bottom-left origin
        // to a PNG's top-left one is the part every first attempt gets wrong, and two copies of it
        // would be two chances to get it wrong.
        if (!FramebufferCapture.writePng(path, client.window().width(), client.window().height())) {
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

    /** The same shape as {@link #parsePoint}, for a pair of board coordinates. */
    private static int[] parseIntPair(String raw) {
        double[] point = parsePoint(raw);
        if (point == null) {
            return null;
        }
        return new int[] {(int) point[0], (int) point[1]};
    }

    /**
     * {@code "3,2,5,2"} -> the board cells {@code (3,2)} then {@code (5,2)}.
     *
     * <p>Pairs rather than one cell because a click-cell hook is only worth having for
     * interactions made of more than one click, and every one of those needs the same conversion
     * twice.
     */
    private static java.util.List<int[]> parseCells(String raw) {
        java.util.List<int[]> cells = new java.util.ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return cells;
        }
        String[] parts = raw.split(",");
        for (int i = 0; i + 1 < parts.length; i += 2) {
            try {
                cells.add(new int[]{Integer.parseInt(parts[i].trim()), Integer.parseInt(parts[i + 1].trim())});
            } catch (NumberFormatException e) {
                // A malformed entry is dropped rather than failing the run: the hook is a
                // development tool, and a typo in a property should not be a crash.
            }
        }
        return cells;
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
