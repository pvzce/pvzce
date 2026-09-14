package com.pvzce.client;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.animation.AnimationManager;
import com.pvzce.client.config.PvzceClientConfig;
import com.pvzce.client.gui.DebugOverlay;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.config.ConfigBuilder;
import com.pvzce.client.gui.config.ConfigCategory;
import com.pvzce.client.gui.config.ConfigEntryBuilder;
import com.pvzce.client.gui.config.ConfigScreen;
import com.pvzce.client.gui.mods.ModMenu;
import com.pvzce.client.gui.mods.ModsScreen;
import com.pvzce.client.gui.screens.ChooseSeedsScreen;
import com.pvzce.client.gui.screens.EditorScreen;
import com.pvzce.client.gui.screens.InGameScreen;
import com.pvzce.client.gui.screens.LevelSaveDialog;
import com.pvzce.client.gui.screens.LevelSelectScreen;
import com.pvzce.client.gui.screens.SettingsScreen;
import com.pvzce.client.gui.screens.TitleScreen;
import com.pvzce.common.PvzceIds;
import com.pvzce.client.gui.screens.WorldSelectScreen;
import com.pvzce.client.particle.ParticleEngine;
import com.pvzce.client.renderer.Matrix4f;
import com.pvzce.client.renderer.PvzceCamera;
import com.pvzce.client.renderer.RenderSystem;
import com.pvzce.client.renderer.ShaderProgram;
import com.pvzce.client.renderer.TextureUv;
import com.pvzce.client.renderer.SpriteRenderer;
import com.pvzce.client.renderer.font.FontRenderer;
import com.pvzce.client.renderer.sprite.Sprite;
import com.pvzce.client.renderer.texture.TextureManager;
import com.pvzce.client.sound.PvzceMusicController;
import com.pvzce.client.sound.SoundEngine;
import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SeedOptions;
import com.pvzce.api.content.DialogueLine;
import com.pvzce.api.content.LevelDef;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelRewardS2C;
import com.pvzce.common.network.packet.LevelSavePromptS2C;
import com.pvzce.common.network.packet.LevelTabsS2C;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.ContinueLevelC2S;
import com.pvzce.common.network.packet.UnlockLevelC2S;
import com.pvzce.common.network.packet.RequestLevelListC2S;
import com.pvzce.common.network.packet.PlayLevelC2S;
import com.pvzce.common.network.packet.SeedOption;
import com.pvzce.common.network.packet.RestartLevelC2S;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.tag.PvzceTags;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.lwjgl.BufferUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.stb.STBImageWrite;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/** Client window + render loop + screen stack; server state stays authority. */
public final class PvzceClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Client");

    private final Connection connection;
    private final Path gameDir;
    private final ClassLoader classLoader;

    private PvzceWindow window;
    private PvzceResourceManager resources;
    private TextureManager textures;
    private FontRenderer font;
    private SoundEngine sound;
    private PvzceMusicController music;
    private AnimationManager animations;
    private PvzceClientConfig config;
    private final ParticleEngine particles = new ParticleEngine();
    private final com.pvzce.client.renderer.liquid.LiquidRipples liquidRipples =
            new com.pvzce.client.renderer.liquid.LiquidRipples();
    private final long startNanos = System.nanoTime();
    private final ClientLevel level = new ClientLevel();
    /**
     * The full-window screens and which one is on top.
     *
     * <p>Navigation goes through this rather than through a local deque so that
     * "replace / push / back" have one definition each, and so a screen that leaves the
     * stack is always told (see {@link com.pvzce.client.gui.Screen#onRemoved()}).
     */
    private final com.pvzce.client.gui.ScreenStack screens =
            new com.pvzce.client.gui.ScreenStack(this);
    /**
     * The layer drawn over the current screen without joining the navigation stack.
     *
     * <p>The console is the only one today. Being outside {@link #screens} is the point:
     * the screen underneath keeps its own lifecycle, and the stack depth keeps meaning
     * "how many screens are nested", which is what screens reason about.
     */
    private com.pvzce.client.gui.Overlay overlay;

    private String currentWorld = "world";
    private volatile List<LevelListS2C.LevelInfo> levelList = List.of();
    private volatile List<LevelTabsS2C.Tab> levelTabs = List.of();
    /** Coins and unlocks of the current world; menus only, the server is authoritative. */
    private final ClientProfile profile = new ClientProfile();
    private final Map<String, List<String>> rememberedSeeds = new LinkedHashMap<>();
    private Path seedSelectionFile;
    private long clientTick;
    private int lastWindowWidth;
    private int lastWindowHeight;
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
    /** Set while waiting for the level list that follows a test-run reload. */
    private String pendingTestLevelId;
    private long backspaceNextNanos;
    private boolean backspaceHeld;
    private boolean leftMouseWasDown;
    private char suppressNextChar;
    private boolean debugOverlayEnabled;
    private boolean savePromptOpen;
    private LevelSavePromptS2C deferredSavePrompt;
    /**
     * Level id whose fresh run was started without passing the seed chooser.
     *
     * <p>The chooser shows a level's opening dialogue itself, so a level that goes through
     * it must not repeat the conversation in game. A level entered directly - a conveyor
     * level, which has no cards to choose - has only the in-game screen left, and this is
     * how that screen learns to show it. Consumed by {@link #onLevelInit}; a resume never
     * sets it, so continuing a save does not replay the conversation.
     */
    private String directDialogueLevelId;
    private int fps;
    private int fpsFrames;
    private long fpsSampleNanos;
    private float worldLightX;
    private float worldLightY;
    private boolean shaderEffectsActive;
    /**
     * The resolved day/night state, kept so passes that are not the sprite pass can
     * read the same numbers instead of recomputing the cycle. The liquid shader
     * needs exactly these; duplicating the maths in {@code applyTimeOfDayShader}
     * is the one thing the architecture doc warns against.
     */
    private float worldTintR = 1F;
    private float worldTintG = 1F;
    private float worldTintB = 1F;
    private float worldTintLift;
    private float worldLightR = 1F;
    private float worldLightG = 1F;
    private float worldLightB = 1F;
    private float worldLightStrength = 0.2F;
    private float worldNightBlend;
    /** Horizontal compensation for the 80x100 PvZ board cells; 1 in GUI/overlay space. */
    private float spriteXScale = 1F;
    private final java.util.Set<Identifier> missingTextures = new HashSet<>();
    private final com.pvzce.client.gui.Clipping clipping = new com.pvzce.client.gui.Clipping(this);

    public PvzceClient(Connection connection, Path gameDir, ClassLoader classLoader) {
        this.connection = connection;
        this.gameDir = gameDir;
        this.classLoader = classLoader;
        // Which mechanic draws which HUD, registered before any level can arrive. A mod
        // adds its own from a ClientModInitializer; the built-ins are here so a mechanic
        // that ships with the game always has its client half.
        com.pvzce.client.mechanic.ClientMechanics.bootstrap();
    }

    public void run() throws Exception {
        PvzcePackets.register();
        Files.createDirectories(gameDir);
        resources = new PvzceResourceManager(classLoader);
        resources.init(gameDir);
        // Animation files are parsed lazily and cached; a reload must drop both the
        // parsed files and the "missing/broken" marks, otherwise a fixed animation
        // stays invisible for the rest of the session.
        if (animations != null) {
            animations.invalidate();
        }
        // Same reason as the animations above: which scene elements are liquid is
        // cached by id, so a reload that changes a definition has to drop the cache
        // or the old answer would be used for the rest of the session.
        com.pvzce.client.renderer.liquid.LiquidTextures.invalidate();
        // Display names come from the pack stack too, so a pack that adds or renames
        // content updates the editor's palette on the same reload as the content.
        com.pvzce.client.gui.GuiLang.reload(resources);
        var tagResult = PvzceTags.MANAGER.reload(resources, BuiltInRegistries.ACCESS);
        for (String error : tagResult.errors()) {
            // The client used to discard this result entirely, so a broken tag file
            // was silent on the client and reported only on the server.
            LOGGER.warn("[tags] {}", error);
        }

        config = PvzceClientConfig.load(gameDir);
        loadSeedSelections();
        window = new PvzceWindow("PVZ Community Edition", config);
        lastWindowWidth = window.width();
        lastWindowHeight = window.height();
        RenderSystem.init();
        // Seed the shader gate from the config as well as setting it in
        // beginWorldView, because the boards that render OUTSIDE a running level -
        // the seed chooser's preview and the editor's canvas - never call
        // beginWorldView, and without this they would take the shader path even
        // when the player has turned shaders off.
        RenderSystem.setShaderEffectsEnabled(config.shadersEnabled());
        textures = new TextureManager(resources);
        font = new FontRenderer(textures, resources);
        sound = new SoundEngine(resources);
        sound.setMasterVolume(config.masterVolume());
        sound.setMusicVolume(config.musicVolume());
        sound.setSfxVolume(config.sfxVolume());
        music = new PvzceMusicController(sound);
        animations = new AnimationManager(this, level, resources);
        level.setAnimationManager(animations);
        Button.setClickSoundHandler(() -> sound.play("pvzce:sfx/ui/click", 1F, 1F));
        connection.setListener(new PvzceClientPacketListener(this, level));
        ModMenu.setBuiltinConfigFactory(parent -> buildSettingsConfig());

        String smokeScreen = System.getProperty("pvzce.smokeScreen", "");
        if ("mods".equals(smokeScreen)) {
            setScreenReplacing(new ModsScreen(this));
        } else if ("settings".equals(smokeScreen)) {
            setScreenReplacing(new SettingsScreen(this));
        } else if ("console".equals(smokeScreen)) {
            // The console is an overlay, so the run needs a screen for it to float over.
            setScreenReplacing(new TitleScreen(this));
            openConsole("");
        } else if ("create".equals(smokeScreen)) {
            com.pvzce.client.gui.screens.LevelSelectScreen levels =
                    new com.pvzce.client.gui.screens.LevelSelectScreen(this);
            setScreenReplacing(levels);
            com.pvzce.client.gui.screens.LevelCreateDialog create =
                    // Same callback LevelSelectScreen uses, so the smoke run exercises the
                    // real confirm -> editor path rather than a print-only stub.
                    com.pvzce.client.gui.screens.LevelCreateDialog.create(this, null, null, request -> {
                        System.out.println("[SMOKE] new-level dialog confirmed: " + request.id());
                        openNewLevelEditor(request.id(), request.name(),
                                request.width(), request.height());
                    });
            levels.showDialog(create);
        } else if ("levels".equals(smokeScreen)) {
            // The level list is where "new level" and "edit level" now live, and it needs
            // a level list from the server to show anything. The world has to be named:
            // the list is per world, and an unnamed one comes back empty.
            setCurrentWorld(System.getProperty("pvzce.smokeWorld", "world"));
            setScreenReplacing(new com.pvzce.client.gui.screens.LevelSelectScreen(this));
            connection.send(new com.pvzce.common.network.packet.RequestLevelListC2S(currentWorld));
        } else if ("inventory".equals(smokeScreen) || "backpack".equals(smokeScreen)) {
            setScreenReplacing(new com.pvzce.client.gui.screens.InventoryScreen(this));
        } else if ("award".equals(smokeScreen)) {
            // The award page only reads the reward packet, so a synthetic one is enough
            // to render it - winning a level to reach it would make the screen
            // unreachable for a screenshot run. Same spirit as the "create" key below,
            // which posts a real dialog with a stub confirm callback.
            setScreenReplacing(new com.pvzce.client.gui.screens.AwardScreen(this,
                    new com.pvzce.common.network.packet.LevelRewardS2C(
                            "pvzce:yard/adventure/1_1", 12, 100, 462, "pvzce:sunflower")));
        } else if ("award_money".equals(smokeScreen)) {
            // The other branch: nothing unlocked, so the frame shows the money bag.
            setScreenReplacing(new com.pvzce.client.gui.screens.AwardScreen(this,
                    new com.pvzce.common.network.packet.LevelRewardS2C(
                            "pvzce:yard/adventure/1_1", 0, 100, 462, "")));
        } else {
            setScreenReplacing(new TitleScreen(this));
        }

        long nextFrameNanos = System.nanoTime();
        while (!window.shouldClose()) {
            window.pollEvents();
            clientTick++;

            connection.tick();

            // Development smoke hook: request a level automatically so CI can
            // render gameplay without driving the title/level screens.
            if (!smokeLevel.isBlank() && !smokeLevelRequested && clientTick > 2) {
                smokeLevelRequested = true;
                if (smokeDialogue) {
                    requestFreshRunDirectly(smokeLevel, smokeLevelRestart);
                } else {
                    requestLevel(smokeLevel, smokeLevelRestart);
                }
            }
            if (!smokeReward.isBlank() && !smokeRewardFired && clientTick > 90
                    && currentScreen() instanceof InGameScreen) {
                smokeRewardFired = true;
                String card = "unlock".equals(smokeReward) ? "pvzce:sunflower" : "";
                level.setGameState(com.pvzce.common.network.packet.GameStateS2C.WON, "pvzce:plant_team");
                // A spot on the right of the lane rather than the middle, so the
                // screenshot proves the reward lands where it is told to.
                onLevelReward(new LevelRewardS2C("pvzce:yard/adventure/1_1", 12, 100, 462, card,
                        6.5F, 0F));
            }
            if (!smokeSeedLevel.isBlank() && !smokeSeedListRequested && clientTick > 2) {
                smokeSeedListRequested = true;
                connection.send(new RequestLevelListC2S(currentWorld));
            }
            // Development smoke hook: drive the console once a level is actually running.
            if (!smokeCommands.isBlank() && !smokeCommandsSent
                    && currentScreen() instanceof com.pvzce.client.gui.screens.InGameScreen) {
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
                        connection.send(new CommandC2S(line));
                    }
                }
            }
            // Development smoke hook for the editor: the editor is four screens deep
            // (title -> world -> level list -> editor), which no smoke run could
            // reach, so screenshots of it were impossible to automate.
            if (!smokeEditorOpened && clientTick > 20 && currentScreen() instanceof TitleScreen) {
                if (!smokeEditorLevel.isBlank()) {
                    smokeEditorOpened = true;
                    Identifier editorId = Identifier.tryParse(smokeEditorLevel);
                    if (editorId != null) {
                        openEditor(editorId);
                    }
                } else if (!smokeNewEditor.isBlank()) {
                    smokeEditorOpened = true;
                    Identifier editorId = Identifier.tryParse(smokeNewEditor);
                    if (editorId != null) {
                        openNewLevelEditor(editorId, "冒烟测试关卡", 9, 5);
                    }
                }
            }
            if (smokeEditorOpened && !smokeEditorHookDone && clientTick > 40
                    && currentScreen() instanceof EditorScreen editor) {
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

            pollInput();
            Screen screen = currentScreen();
            screen.initIfNeeded();
            screen.tick();
            music.tick();
            animations.tick();
            render();

            // Development smoke hook: click a GUI point given in logical GUI coordinates,
            // so a dialog's buttons can be exercised without a human at the mouse.
            // Click a dialog button by its label: dialog geometry depends on the window
            // size, so a hard-coded point is easy to get wrong and silently misses.
            if (!smokeClickLabel.isBlank() && !smokeClickDone && clientTick == smokeClickFrame) {
                for (com.pvzce.client.gui.components.Dialog dialog : currentScreen().dialogs()) {
                    for (com.pvzce.client.gui.components.AbstractWidget child : dialog.children()) {
                        if (child instanceof com.pvzce.client.gui.components.Button button
                                && smokeClickLabel.equals(button.label())) {
                            smokeClickDone = true;
                            int cx = child.x() + child.width() / 2;
                            int cy = child.y() + child.height() / 2;
                            System.out.println("[SMOKE] clicking '" + smokeClickLabel + "' at " + cx + "," + cy);
                            deliverGuiClick(cx, cy, 0);
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
                double rawX = gui[0] * window.width() / (double) Math.max(1, guiWidth());
                double rawY = window.height() - gui[1] * window.height() / (double) Math.max(1, guiHeight());
                System.out.println("[CLICKDEBUG] gui=" + gui[0] + "," + gui[1]
                        + " raw=" + rawX + "," + rawY
                        + " backToGui=" + guiMouseX(rawX) + "," + guiMouseY(rawY)
                        + " screen=" + currentScreen().getClass().getSimpleName());
                deliverRawClick(rawX, rawY, 0);
            }
            if (smokeHoverAt != null && clientTick >= smokeHoverFrame) {
                // The same conversion smokeClick uses, and for the same reason: the cursor is
                // reported top-down while GUI Y grows upwards, so a point that is 40% up the
                // GUI is 60% down the window. Getting this backwards points the "hover" at the
                // vertical mirror of the cell under test, which is exactly the kind of
                // off-by-a-reflection a screenshot is supposed to catch.
                double rawX = smokeHoverAt[0] * window.width() / (double) Math.max(1, guiWidth());
                double rawY = window.height()
                        - smokeHoverAt[1] * window.height() / (double) Math.max(1, guiHeight());
                window.warpCursor(rawX, rawY);
            }
            if (smokeDragTo != null && !smokeDragDone && clientTick == smokeDragFrame) {
                smokeDragDone = true;
                double[] gui = smokeDragTo;
                double rawX = gui[0] * window.width() / (double) Math.max(1, guiWidth());
                double rawY = window.height() - gui[1] * window.height() / (double) Math.max(1, guiHeight());
                System.out.println("[SMOKE] dragging to gui=" + gui[0] + "," + gui[1]
                        + " backToGui=" + guiMouseX(rawX) + "," + guiMouseY(rawY));
                deliverRawDrag(rawX, rawY, 0);
                deliverRawRelease(rawX, rawY, 0);
            }
            if (smokeFrames > 0 && clientTick == smokeFrames) {
                System.out.println("[SMOKE] frame " + smokeFrames + " rendered, screen=" + screen.getClass().getSimpleName());
                break;
            }
            if (capturePath != null && clientTick == captureFrame) {
                capture(capturePath);
            }
            window.swapBuffers();

            // MC-style software frame limit (active below the "unlimited" cap).
            if (config.maxFps() < PvzceClientConfig.UNLIMITED_FPS) {
                long frameBudget = 1_000_000_000L / config.maxFps();
                nextFrameNanos += frameBudget;
                long remaining = nextFrameNanos - System.nanoTime();
                if (remaining > 0) {
                    LockSupport.parkNanos(remaining);
                } else if (remaining < -frameBudget) {
                    nextFrameNanos = System.nanoTime();
                }
            }
        }

        connection.send(new LeaveLevelC2S());
        connection.tick();
        connection.disconnect("window closed");
        if (music != null) {
            music.stopAll();
        }
        if (sound != null) {
            sound.close();
        }
        textures.close();
        window.close();
    }

    // ---------- input ----------

    private void pollInput() {
        Screen screen = currentScreen();
        if (overlay != null) {
            // An overlay builds its widgets lazily, exactly like a screen: it needs a window
            // to size them against, and it is created the moment the player asks for it.
            overlay.initIfNeeded();
        }
        Integer key;
        while ((key = window.pollKey()) != null) {
            if (key == GLFW.GLFW_KEY_F11) {
                setFullscreen(!window.isFullscreen());
                continue;
            }
            if (key == GLFW.GLFW_KEY_F3) {
                debugOverlayEnabled = !debugOverlayEnabled;
                continue;
            }
            // An open overlay owns the keyboard, console shortcuts included: the console
            // itself must not be able to open a second console, and ESC belongs to it.
            if (overlay != null) {
                overlay.keyPressed(key);
                continue;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE
                    && !(screen instanceof InGameScreen)
                    && !(screen instanceof EditorScreen)) {
                navigateBack();
                screen = currentScreen();
                continue;
            }
            if (!screen.hasTextInputFocused()) {
                if (key == GLFW.GLFW_KEY_SLASH) {
                    suppressNextChar = '/';
                    openConsole("/");
                    continue;
                }
                if (key == GLFW.GLFW_KEY_T) {
                    suppressNextChar = 't';
                    openConsole("");
                    continue;
                }
            }
            screen.keyPressed(key);
        }
        Integer codepoint;
        while ((codepoint = window.pollTypedChar()) != null) {
            char ch = (char) codepoint.intValue();
            if (suppressNextChar != 0 && Character.toLowerCase(ch) == suppressNextChar) {
                suppressNextChar = 0;
                continue;
            }
            suppressNextChar = 0;
            if (overlay != null) {
                overlay.charTyped(ch);
            } else {
                currentScreen().charTyped(ch);
            }
        }
        screen = currentScreen();
        boolean backspaceDown = window.isKeyDown(GLFW.GLFW_KEY_BACKSPACE);
        boolean textFocused = overlay != null
                ? overlay.hasTextInputFocused() : screen.hasTextInputFocused();
        if (backspaceDown && textFocused) {
            long now = System.nanoTime();
            if (!backspaceHeld) {
                // The press itself already deleted one character via keyPressed;
                // only schedule the first frame-rate independent auto-repeat.
                backspaceNextNanos = now + 500_000_000L;
            }
            if (now >= backspaceNextNanos) {
                if (overlay != null) {
                    overlay.keyPressed(GLFW.GLFW_KEY_BACKSPACE);
                } else {
                    screen.keyPressed(GLFW.GLFW_KEY_BACKSPACE);
                }
                backspaceNextNanos = now + 80_000_000L;
            }
        }
        backspaceHeld = backspaceDown;
        Integer button;
        while ((button = window.pollMouseButton()) != null) {
            if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                dispatchMouseClicked(button);
            }
        }
        boolean leftDown = window.isMouseButtonDown(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        if (leftDown) {
            dispatchMouseDragged();
        } else if (leftMouseWasDown) {
            dispatchMouseReleased();
        }
        leftMouseWasDown = leftDown;
        Double scroll;
        while ((scroll = window.pollScroll()) != null) {
            dispatchMouseScrolled(scroll);
        }
        if (overlay != null) {
            overlay.mouseMoved(guiMouseX(window.cursorX()), guiMouseY(window.cursorY()));
        } else {
            currentScreen().mouseMoved(window.cursorX(), window.cursorY());
        }
    }

    // ------------------------------------------------------------------
    // Pointer dispatch
    //
    // One place decides whether the mouse goes to the overlay or to the screen, so the
    // frame loop cannot drift from itself: every branch below reads the same rule, and the
    // raw-framebuffer-to-GUI conversion happens exactly once per event.
    // ------------------------------------------------------------------

    private void dispatchMouseClicked(int button) {
        double guiX = guiMouseX(window.cursorX());
        double guiY = guiMouseY(window.cursorY());
        if (overlay != null) {
            overlay.mouseClicked(guiX, guiY, button);
        } else {
            currentScreen().dispatchMouseClicked(guiX, guiY, button);
        }
    }

    private void dispatchMouseDragged() {
        double guiX = guiMouseX(window.cursorX());
        double guiY = guiMouseY(window.cursorY());
        if (overlay != null) {
            overlay.mouseDragged(guiX, guiY, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        } else {
            currentScreen().mouseDragged(window.cursorX(), window.cursorY(),
                    GLFW.GLFW_MOUSE_BUTTON_LEFT);
        }
    }

    private void dispatchMouseReleased() {
        double guiX = guiMouseX(window.cursorX());
        double guiY = guiMouseY(window.cursorY());
        if (overlay != null) {
            overlay.mouseReleased(guiX, guiY, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        } else {
            currentScreen().mouseReleased(window.cursorX(), window.cursorY(),
                    GLFW.GLFW_MOUSE_BUTTON_LEFT);
        }
    }

    private void dispatchMouseScrolled(double amount) {
        double guiX = guiMouseX(window.cursorX());
        double guiY = guiMouseY(window.cursorY());
        if (overlay != null) {
            overlay.mouseScrolled(guiX, guiY, amount);
        } else {
            currentScreen().mouseScrolled(window.cursorX(), window.cursorY(), amount);
        }
    }

    /** Opens the console over the current screen; {@code initialContents} pre-fills it. */
    private void openConsole(String initialContents) {
        if (overlay == null) {
            overlay = new com.pvzce.client.gui.ConsoleOverlay(this, initialContents);
        }
    }

    /**
     * Closes {@code expected}, if it is still the open overlay.
     *
     * <p>The argument is what makes this safe to call from inside the overlay itself: it
     * cannot close a different overlay that replaced it in the meantime, which is the kind
     * of stale-callback bug a bare {@code closeOverlay()} would invite.
     */
    public void dismissOverlay(com.pvzce.client.gui.Overlay expected) {
        if (overlay == expected) {
            overlay = null;
        }
    }

    /** The overlay currently drawn over the screen, or {@code null}. */
    public com.pvzce.client.gui.Overlay overlay() {
        return overlay;
    }

    // ------------------------------------------------------------------
    // Development smoke hooks: synthetic input
    //
    // These exist so a screenshot run can drive a dialog without a human at the mouse, and
    // they go through the same dispatch rule the real event loop uses - otherwise a smoke
    // run would photograph a state the player could never reach.
    // ------------------------------------------------------------------

    /** A click in logical GUI coordinates, to whichever layer owns the mouse. */
    private void deliverGuiClick(double guiX, double guiY, int button) {
        if (overlay != null) {
            overlay.mouseClicked(guiX, guiY, button);
        } else {
            currentScreen().dispatchMouseClicked(guiX, guiY, button);
        }
    }

    /** A click in raw framebuffer coordinates, to whichever layer owns the mouse. */
    private void deliverRawClick(double rawX, double rawY, int button) {
        if (overlay != null) {
            overlay.mouseClicked(guiMouseX(rawX), guiMouseY(rawY), button);
        } else {
            currentScreen().mouseClicked(rawX, rawY, button);
        }
    }

    private void deliverRawDrag(double rawX, double rawY, int button) {
        if (overlay != null) {
            overlay.mouseDragged(guiMouseX(rawX), guiMouseY(rawY), button);
        } else {
            currentScreen().mouseDragged(rawX, rawY, button);
        }
    }

    private void deliverRawRelease(double rawX, double rawY, int button) {
        if (overlay != null) {
            overlay.mouseReleased(guiMouseX(rawX), guiMouseY(rawY), button);
        } else {
            currentScreen().mouseReleased(rawX, rawY, button);
        }
    }

    // ---------- rendering ----------

    private void render() {
        if (window.width() != lastWindowWidth || window.height() != lastWindowHeight) {
            lastWindowWidth = window.width();
            lastWindowHeight = window.height();
            currentScreen().onResize();
        }
        RenderSystem.clear(0.53F, 0.75F, 0.98F, 1F);
        RenderSystem.setShader();
        updateFps();
        currentScreen().render();
        if (overlay != null) {
            overlay.initIfNeeded();
            overlay.render();
        }
        if (debugOverlayEnabled) {
            beginGuiView();
            DebugOverlay.render(this);
        }
        // Safety net: every push has a matching pop, but a crash mid-draw must not
        // leave the scissor test enabled for the rest of the session.
        clipping.reset();
        RenderSystem.checkGlError("frame");
    }

    /** One-second sliding window FPS sample, refreshed twice per second like MC's debug chart. */
    private void updateFps() {
        fpsFrames++;
        long now = System.nanoTime();
        long elapsed = now - fpsSampleNanos;
        if (elapsed >= 500_000_000L) {
            fps = (int) Math.round(fpsFrames * 1_000_000_000D / elapsed);
            fpsFrames = 0;
            fpsSampleNanos = now;
        }
    }

    /** World-space viewport/projection for gameplay rendering. */
    public PvzceCamera beginWorldView() {
        return beginWorldView(camera());
    }

    /**
     * The same, for a camera the caller has already adjusted.
     *
     * <p>Only the end of a level uses it: a defeat looks toward the house (see
     * {@code PvzceCamera.panned}), and the projection, the sprite scale and the shader
     * lights all have to come from that camera rather than from a second one built here.
     */
    public PvzceCamera beginWorldView(PvzceCamera camera) {
        spriteXScale = camera.unitY() / Math.max(0.0001F, camera.unitX());
        RenderSystem.viewport(camera.viewportX(), camera.viewportY(), camera.viewportWidth(), camera.viewportHeight());
        RenderSystem.setProjectionMatrix(camera.projection());
        RenderSystem.setShaderEffectsEnabled(config.shadersEnabled());
        if (config.shadersEnabled()) {
            shaderEffectsActive = true;
            applyTimeOfDayShader();
            applyEntityLights();
        } else {
            // "Shaders" off: neutral lighting, tint and sun/point glows disabled.
            shaderEffectsActive = false;
            worldLightX = level.width() / 2F;
            worldLightY = level.height() * 3F;
            worldTintR = 1F;
            worldTintG = 1F;
            worldTintB = 1F;
            worldTintLift = 0F;
            worldLightR = 1F;
            worldLightG = 1F;
            worldLightB = 1F;
            worldLightStrength = 0.2F;
            worldNightBlend = 0F;
            RenderSystem.setTimeOfDay(1F, 1F, 1F, 0F, 0F, 0F, 0F, 1F, 1F, 1F, 0F);
            RenderSystem.clearPointLights();
        }
        return camera;
    }

    /**
     * Temporary world projection for overlay animations (seed-chooser zombie
     * previews, future alive previews). Coordinates are logical GUI pixels;
     * the method converts the viewport to raw framebuffer pixels for GL.
     */
    public void beginOverlayWorldView(float guiX, float guiY, float guiWidth, float guiHeight,
                                      float worldLeft, float worldRight,
                                      float worldBottom, float worldTop) {
        spriteXScale = 1F;
        int scale = Math.max(1, guiScale());
        int viewportX = Math.round(guiX * scale);
        int viewportY = Math.round(guiY * scale);
        int viewportWidth = Math.max(1, Math.round(guiWidth * scale));
        int viewportHeight = Math.max(1, Math.round(guiHeight * scale));
        RenderSystem.viewport(viewportX, viewportY, viewportWidth, viewportHeight);
        RenderSystem.setProjectionMatrix(Matrix4f.ortho(worldLeft, worldRight, worldBottom, worldTop, -10F, 10F));
        RenderSystem.setGuiShader();
    }

    /** Sun drops act as warm point lights for the board around them. */
    private void applyEntityLights() {
        RenderSystem.clearPointLights();
        float centerX = level.width() / 2F;
        float centerY = level.height() / 2F;
        List<ClientEntity> suns = level.entities().values().stream()
                .filter(PvzceClient::lightsTheBoard)
                .sorted((a, b) -> Float.compare(
                        distanceSq(a.cellX(), a.cellY() + a.height(), centerX, centerY),
                        distanceSq(b.cellX(), b.cellY() + b.height(), centerX, centerY)))
                .toList();
        float nightBlend = level.nightBlendAt(level.smoothDayTicks());
        int lightCount = Math.min(ShaderProgram.MAX_POINT_LIGHTS, suns.size());
        for (int i = 0; i < lightCount; i++) {
            ClientEntity sun = suns.get(i);
            // Sun drops stay warm by day and crossfade to pale moonlit glows at night.
            float r = lerp(1F, 0.72F, nightBlend);
            float g = lerp(0.85F, 0.82F, nightBlend);
            float b = lerp(0.35F, 1F, nightBlend);
            float strength = lerp(0.5F, 0.38F, nightBlend);
            RenderSystem.setPointLight(i, sun.cellX(), sun.cellY() + sun.height(),
                    2.4F, r, g, b, strength);
        }
    }

    private static float distanceSq(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    /**
     * True when this drop is the sun and therefore lights the board.
     *
     * <p>Asked by <em>content id</em>, not by entity kind. A kind is a category - every drop
     * shares one - so the previous {@code kind().equals("sun")} test matched every coin and
     * diamond too, and each one lit the lawn like a small sun. Package-private so a test can
     * hold that distinction down without a GL context.
     */
    static boolean lightsTheBoard(ClientEntity entity) {
        return entity != null && PvzceIds.SUN.toString().equals(entity.defIdString());
    }

    /**
     * Time-of-day shader driven by the client-side interpolated clock so the
     * sun/moon and tint change continuously instead of once per server sync.
     * World x=0 is EAST (left), x=width is WEST (right).
     */
    private void applyTimeOfDayShader() {
        var time = level.timeOfDay();
        float dayTicks = level.smoothDayTicks();
        float nightBlend = level.nightBlendAt(dayTicks);
        int w = Math.max(1, level.width());
        int h = Math.max(1, level.height());

        // Day parameters.
        float dayTintR;
        float dayTintG;
        float dayTintB;
        float dayLift;
        float daySunX;
        float daySunY;
        float daySunR;
        float daySunG;
        float daySunB;
        float dayStrength;
        float dayRadius = Math.max(w, h) * 0.9F;

        if (time.dayLength() <= 0) {
            // Permanent day: sun directly overhead, neutral warm light.
            dayTintR = 1.04F;
            dayTintG = 1.01F;
            dayTintB = 0.94F;
            dayLift = 0.015F;
            dayStrength = 0.20F;
            daySunX = w * 0.5F;
            daySunY = h * 3F;
            daySunR = 1F;
            daySunG = 1F;
            daySunB = 0.9F;
        } else {
            int cycle = Math.max(1, time.dayLength() + Math.max(0, time.nightLength()));
            long dayPos = Math.floorMod((long) Math.floor(dayTicks), cycle);
            int dayLength = Math.max(1, time.dayLength());
            int nightLength = Math.max(0, time.nightLength());
            int window = Math.max(1, Math.min(120, Math.min(dayLength, Math.max(1, nightLength)) / 4));
            // Keep the setting sun in the west through dusk; use the pre-sunrise
            // east position for the final dawn blend.
            float progress = dayPos <= dayLength + window
                    ? Math.min(1F, dayPos / (float) dayLength)
                    : 0F;
            daySunX = w * (0.05F + 0.9F * progress); // east(left) -> west(right)
            daySunY = h * (0.5F + 1.6F * Math.abs((float) Math.sin(Math.PI * progress)));
            dayStrength = 0.22F + 0.16F * (float) Math.sin(Math.PI * progress);
            float morning = Math.max(0F, 1F - progress * 2F);
            float evening = Math.max(0F, (progress - 0.5F) * 2F);
            dayTintR = 1F + 0.08F * morning + 0.10F * evening;
            dayTintG = 1F + 0.02F * morning - 0.15F * evening;
            dayTintB = 1F - 0.10F * morning - 0.30F * evening;
            dayLift = 0.01F;
            daySunR = 1F;
            daySunG = 0.75F + 0.25F * morning - 0.15F * evening;
            daySunB = 0.45F + 0.25F * morning;
        }

        // Night parameters: PvZ-style cool blue moonlight.
        float nightTintR = 0.40F;
        float nightTintG = 0.46F;
        float nightTintB = 0.78F;
        float nightLift = -0.015F;
        float nightStrength = 0.34F;
        float nightSunX = w * 0.72F; // moon above the western side
        float nightSunY = h * 2.1F;
        float nightSunR = 0.68F;
        float nightSunG = 0.80F;
        float nightSunB = 1.0F;
        float nightRadius = Math.max(w, h) * 1.05F;

        float tintR = lerp(dayTintR, nightTintR, nightBlend);
        float tintG = lerp(dayTintG, nightTintG, nightBlend);
        float tintB = lerp(dayTintB, nightTintB, nightBlend);
        float lift = lerp(dayLift, nightLift, nightBlend);
        float sunX = lerp(daySunX, nightSunX, nightBlend);
        float sunY = lerp(daySunY, nightSunY, nightBlend);
        float sunR = lerp(daySunR, nightSunR, nightBlend);
        float sunG = lerp(daySunG, nightSunG, nightBlend);
        float sunB = lerp(daySunB, nightSunB, nightBlend);
        float strength = lerp(dayStrength, nightStrength, nightBlend);
        float radius = lerp(dayRadius, nightRadius, nightBlend);

        worldLightX = sunX;
        worldLightY = sunY;
        worldTintR = tintR;
        worldTintG = tintG;
        worldTintB = tintB;
        worldTintLift = lift;
        worldLightR = sunR;
        worldLightG = sunG;
        worldLightB = sunB;
        worldLightStrength = strength;
        worldNightBlend = nightBlend;
        RenderSystem.setTimeOfDay(tintR, tintG, tintB, lift, sunX, sunY, radius, sunR, sunG, sunB, strength);
    }

    private static float lerp(float a, float b, float t) {
        return com.pvzce.common.util.MathUtil.lerp(a, b, t);
    }

    /** Full-window orthographic projection in logical (scaled) GUI pixels. */
    public void beginGuiView() {
        spriteXScale = 1F;
        RenderSystem.viewport(0, 0, window.width(), window.height());
        RenderSystem.setProjectionMatrix(Matrix4f.ortho(0, guiWidth(), 0, guiHeight(), -10, 10));
        RenderSystem.setGuiShader();
    }

    /**
     * MC {@code Window.calculateScale}-shaped GUI scale. Auto uses the max
     * usable integer scale; a manual scale is clamped by the same constraints.
     */
    /** GUI-space clipping rectangles; see {@link com.pvzce.client.gui.Clipping}. */
    public com.pvzce.client.gui.Clipping clipping() {
        return clipping;
    }

    public int guiScale() {
        return guiScaleFor(config.guiScale() == PvzceClientConfig.AUTO_GUI_SCALE
                ? PvzceClientConfig.MAX_MANUAL_GUI_SCALE
                : config.guiScale());
    }

    /** MC {@code Window.calculateScale(requested, false)}. */
    public int guiScaleFor(int requestedScale) {
        int maxScale = Math.max(1, requestedScale);
        int scale = 1;
        int width = window.width();
        int height = window.height();
        while (scale != maxScale
                && scale < width
                && scale < height
                && width / (scale + 1) >= 320
                && height / (scale + 1) >= 240) {
            scale++;
        }
        return scale;
    }

    public int maxAvailableGuiScale() {
        return guiScaleFor(PvzceClientConfig.MAX_MANUAL_GUI_SCALE);
    }

    public int guiWidth() {
        return ceilDiv(window.width(), guiScale());
    }

    public int guiHeight() {
        return ceilDiv(window.height(), guiScale());
    }

    private static int ceilDiv(int value, int divisor) {
        return com.pvzce.common.util.MathUtil.ceilDiv(value, divisor);
    }

    /** Converts a raw framebuffer mouse X to logical GUI X. */
    public double guiMouseX(double mouseX) {
        return mouseX * guiWidth() / (double) Math.max(1, window.width());
    }

    /** Converts a raw framebuffer mouse Y (top-down) to logical bottom-up GUI Y. */
    public double guiMouseY(double mouseY) {
        return (window.height() - mouseY) * guiHeight() / (double) Math.max(1, window.height());
    }

    public PvzceCamera camera() {
        return new PvzceCamera(window.width(), window.height(), Math.max(1, level.width()), Math.max(1, level.height()));
    }

    /** Horizontal sprite correction so square world-space sprites match the board cell aspect. */
    public float spriteXScale() {
        return spriteXScale;
    }

    public void drawTexture(Identifier id, float x, float y, float w, float h, float z, float r, float g, float b, float a) {
        drawTextureRegion(id, 0F, 0F, 1F, 1F, x, y, w, h, z, r, g, b, a);
    }

    /** Draws a UV sub-region of a texture (u/v increase toward the world's +x/+y). */
    /**
     * Multiplier applied to every colour drawn while it is on the stack.
     *
     * <p>One stack rather than a field: a part sheet is drawn by a loop that may itself
     * call back into a tinted draw, and a single field would leak the tint outwards.
     * Sprites are 1x1; this is for art that is too bright to read at its own values.
     */
    private final java.util.ArrayDeque<float[]> entityInk = new java.util.ArrayDeque<>();

    /** Dims everything drawn until the matching {@link #popEntityTint}. */
    public void pushEntityTint(float multiplier) {
        float[] current = entityInk();
        entityInk.push(new float[]{clamp(current[0] * multiplier), current[1]});
    }

    public void popEntityTint() {
        entityInk.poll();
    }

    /**
     * Fades everything drawn until the matching {@link #popEntityAlpha}.
     *
     * <p>The sibling of {@link #pushEntityTint}, and a stack for the same reason. It exists
     * because the translucent preview of a plant being dragged onto the lawn has to fade the
     * plant's <em>animation</em> - and the animation path hard-codes alpha 1, so there was no
     * way to draw one faded without a second renderer.
     *
     * <p>Both pushes multiply into the current value rather than replacing it, so a faded
     * draw inside a dimmed one is dim <em>and</em> faded.
     */
    public void pushEntityAlpha(float multiplier) {
        float[] current = entityInk();
        entityInk.push(new float[]{current[0], clamp(current[1] * multiplier)});
    }

    public void popEntityAlpha() {
        entityInk.poll();
    }

    private float[] entityInk() {
        float[] ink = entityInk.peek();
        return ink == null ? new float[]{1F, 1F} : ink;
    }

    private static float clamp(float value) {
        return Math.max(0F, Math.min(1F, value));
    }

    public void drawTextureRegion(Identifier id, float u0, float v0, float u1, float v1,
                                  float x, float y, float w, float h, float z, float r, float g, float b, float a) {
        float[] ink = entityInk();
        try {
            SpriteRenderer.textured(new Sprite(textures.getOrLoad(id), u0, v0, u1, v1), x, y, w, h, z,
                    r * ink[0], g * ink[0], b * ink[0], a * ink[1]);
        } catch (Exception e) {
            warnMissingTexture(id);
            drawMissingTexture(x, y, w, h, z, r * ink[0], g * ink[0], b * ink[0], a * ink[1]);
        }
    }

    /**
     * Draws every texture the renderer falls back to: this one is what a missing reference
     * looks like.
     *
     * <p>Never recurses into {@link #drawTextureRegion}, so a build without the placeholder
     * still draws something instead of throwing from inside the fallback.
     */
    private void drawMissingTexture(float x, float y, float w, float h, float z,
                                    float r, float g, float b, float a) {
        try {
            SpriteRenderer.textured(new Sprite(textures.getOrLoad(com.pvzce.common.core.EntityArt.MISSING_TEXTURE),
                            0F, 0F, 1F, 1F),
                    x, y, w, h, z, r, g, b, a);
        } catch (Exception e) {
            SpriteRenderer.solid(x, y, w, h, z, r, g, b, a);
        }
    }

    /**
     * Draws an arbitrary textured quad (bl, br, tr, tl) for controller parts.
     * Corner UVs are in top-left pixel coordinates; this method normalizes
     * them against the loaded texture (which STB uploads vertically flipped).
     */
    public void drawTextureQuad(Identifier id,
                                float x0, float y0, float x1, float y1,
                                float x2, float y2, float x3, float y3,
                                float u0, float v0, float u1, float v1,
                                float u2, float v2, float u3, float v3,
                                float z, float r, float g, float b, float a) {
        float[] ink = entityInk();
        float cr = r * ink[0];
        float cg = g * ink[0];
        float cb = b * ink[0];
        float ca = a * ink[1];
        try {
            var texture = textures.getOrLoad(id);
            SpriteRenderer.texturedQuad(texture,
                    x0, y0, x1, y1, x2, y2, x3, y3,
                    TextureUv.normalizeU(u0, texture.width()), TextureUv.normalizeV(v0, texture.height()),
                    TextureUv.normalizeU(u1, texture.width()), TextureUv.normalizeV(v1, texture.height()),
                    TextureUv.normalizeU(u2, texture.width()), TextureUv.normalizeV(v2, texture.height()),
                    TextureUv.normalizeU(u3, texture.width()), TextureUv.normalizeV(v3, texture.height()),
                    z, cr, cg, cb, ca);
        } catch (Exception e) {
            warnMissingTexture(id);
            // The part's own quad, kept where it was: a part is drawn in the middle of a
            // posed skeleton, and a placeholder that snapped to an axis-aligned box would
            // move it. Squashed to the quad's bounding box, which is what a missing part
            // should look like when the art around it is still there.
            drawMissingTexture(Math.min(Math.min(x0, x1), Math.min(x2, x3)),
                    Math.min(Math.min(y0, y1), Math.min(y2, y3)),
                    Math.max(Math.max(x0, x1), Math.max(x2, x3)) - Math.min(Math.min(x0, x1), Math.min(x2, x3)),
                    Math.max(Math.max(y0, y1), Math.max(y2, y3)) - Math.min(Math.min(y0, y1), Math.min(y2, y3)),
                    z, cr, cg, cb, ca);
        }
    }

    /** True when a texture can be resolved from the built-in pack or an active resource pack. */
    public boolean hasTexture(Identifier id) {
        return resources.hasTexture(id);
    }

    /**
     * Light-source projected entity shadow rendered by the shader shadow
     * pass. The sprite silhouette is projected from the current interpolated
     * sun/moon position onto the ground plane, and its tint crossfades with
     * the smooth day/night factor.
     */
    public void drawEntityShadow(Identifier texture, float centerX, float centerY,
                                 float width, float height, float alpha) {
        float lightX = worldLightX;
        float lightY = worldLightY;
        if (!shaderEffectsActive) {
            lightX = centerX;
            lightY = centerY + height * 4F;
        }
        float nightBlend = level.nightBlendAt(level.smoothDayTicks());
        float shadowR = lerp(0.02F, 0.03F, nightBlend);
        float shadowG = lerp(0.03F, 0.06F, nightBlend);
        float shadowB = lerp(0.08F, 0.22F, nightBlend);
        try {
            SpriteRenderer.projectedShadow(Sprite.whole(textures.getOrLoad(texture)),
                    lightX, lightY, centerY, centerX, width, height, 0.04F,
                    shadowR, shadowG, shadowB, alpha);
        } catch (Exception e) {
            warnMissingTexture(texture);
            SpriteRenderer.shadow(centerX, centerY, width / 2F, Math.max(0.06F, width * 0.12F),
                    0.04F, shadowR, shadowG, shadowB, alpha * 0.6F);
        }
    }

    public float worldLightX() {
        return worldLightX;
    }

    public float worldLightY() {
        return worldLightY;
    }

    /**
     * Wall-clock seconds since the client started, for continuously scrolling
     * effects.
     *
     * <p>Deliberately not the server tick clock: the liquid surface keeps flowing
     * while the game is paused or the tick rate is frozen, and a player who pauses
     * should not come back to a surface that has to catch up.
     */
    public float renderTimeSeconds() {
        return (System.nanoTime() - startNanos) / 1_000_000_000F;
    }

    /** The resolved day/night state; see the field comment for why it is cached. */
    public float worldTintR() {
        return worldTintR;
    }

    public float worldTintG() {
        return worldTintG;
    }

    public float worldTintB() {
        return worldTintB;
    }

    public float worldTintLift() {
        return worldTintLift;
    }

    public float worldLightR() {
        return worldLightR;
    }

    public float worldLightG() {
        return worldLightG;
    }

    public float worldLightB() {
        return worldLightB;
    }

    public float worldLightStrength() {
        return worldLightStrength;
    }

    public float worldNightBlend() {
        return worldNightBlend;
    }

    /** Surface disturbances from server effect events. */
    public com.pvzce.client.renderer.liquid.LiquidRipples liquidRipples() {
        return liquidRipples;
    }

    public boolean shaderEffectsActive() {
        return shaderEffectsActive;
    }

    /** MC-style missing-texture warning, logged once per identifier. */
    public void warnMissingTexture(Identifier id) {
        if (missingTextures.add(id)) {
            LOGGER.warn("Missing texture reference: {}", id);
            if (Boolean.getBoolean("pvzce.traceTextures")) {
                new Throwable("missing " + id).printStackTrace();
            }
        }
    }

    private static float clamp(float value, float min, float max) {
        return com.pvzce.common.util.MathUtil.clamp(value, min, max);
    }

    public void drawSolid(float x, float y, float w, float h, float z, float r, float g, float b, float a) {
        SpriteRenderer.solid(x, y, w, h, z, r, g, b, a);
    }

    // ---------- screen stack ----------

    /**
     * Installs a screen as the new root, discarding the ones below it.
     *
     * <p>For "the player is now somewhere else": a level ended, the world changed, a save
     * was reloaded. Screens discarded this way are told they are leaving
     * ({@code Screen.onRemoved}), so a preview or an animation playback they were holding
     * cannot outlive them.
     */
    public void setScreenReplacing(Screen screen) {
        screens.replace(screen);
    }

    /** Nests a screen on top of the current one; the screen below stays alive. */
    public void openScreen(Screen screen) {
        screens.push(screen);
    }

    /**
     * Leaves the current screen the way it asked to be left.
     *
     * <p>Replaces the old "pop and hope" contract: a screen states its destination
     * ({@link com.pvzce.client.gui.Screen#backTarget()}), so the level list no longer has to
     * infer from the stack depth whether the world list is underneath it, and a screen that
     * is alone on the stack cannot be popped into a client with nothing to render.
     */
    public void navigateBack() {
        screens.back();
    }

    /**
     * Pops the current screen, when there is one underneath.
     *
     * <p>For the client's own "hand the view back" moves - the editor's 测试 says "I am done,
     * show what is behind me" rather than naming a destination. Screens navigate with
     * {@link #navigateBack()} and their own {@code backTarget()}; this is the one caller that
     * genuinely means "just go back one" without caring what that is.
     */
    public void popScreen() {
        if (screens.depth() > 1) {
            screens.back();
        }
    }

    public Screen currentScreen() {
        return screens.current();
    }

    /** How many screens are on the stack; diagnostics and tests. Overlays are not counted. */
    public int screenDepth() {
        return screens.depth();
    }

    /** Opens or closes the command console; it floats over the current screen. */
    public void toggleConsole() {
        if (overlay instanceof com.pvzce.client.gui.ConsoleOverlay) {
            dismissOverlay(overlay);
        } else {
            openConsole("");
        }
    }

    /**
     * Opens the editor for an existing level.
     *
     * <p>Takes the level's id, not a file name: the editor used to be handed a
     * hard-coded {@code "custom_level"} from a single button, so every visit edited
     * the same level and anything saved under another name was unreachable.
     */
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

    public void openEditor(Identifier levelId) {
        openScreen(new EditorScreen(this, levelId));
    }

    /** Opens the editor for a brand-new level described by the create dialog. */
    public void openNewLevelEditor(Identifier levelId, String name, int width, int height) {
        openScreen(new EditorScreen(this, levelId, name, width, height));
    }

    public void showTitle() {
        setScreenReplacing(new TitleScreen(this));
    }

    public void showWorldSelect() {
        setScreenReplacing(new WorldSelectScreen(this));
    }

    /**
     * The peer speaks a different wire format. Refusing loudly beats decoding its
     * packets as the wrong types.
     */
    public void onProtocolMismatch(int serverVersion, int clientVersion) {
        String message = "协议版本不一致：服务器 " + serverVersion + "，客户端 " + clientVersion
                + "。请使用同一版本的游戏。";
        level.setDisconnected(message);
        LOGGER.error(message);
        showWorldSelect();
    }

    public void onLevelInit() {
        particles.clear();
        if (animations != null) {
            animations.clear();
        }
        savePromptOpen = false;
        if (music != null) {
            music.startLevel("pvzce:music/grasswalk");
        }
        // Only a run that skipped the seed chooser still owes the player its opening
        // conversation; one that went through the chooser has already shown it.
        String levelId = level.levelId();
        List<DialogueLine> opening = levelId != null && levelId.equals(directDialogueLevelId)
                ? levelDialogue(levelId) : List.of();
        directDialogueLevelId = null;
        setScreenReplacing(new InGameScreen(this, opening));
        if (deferredSavePrompt != null) {
            LevelSavePromptS2C prompt = deferredSavePrompt;
            deferredSavePrompt = null;
            showLevelSavePrompt(prompt);
        }
    }

    /**
     * Starts a level without going through the pre-game screens: used by editor tests, smoke
     * hooks and command helpers, which have no player to ask.
     *
     * <p>Carries no card bar, so the server uses the level's own cards - the same thing the
     * seed chooser would produce for a level that fixes its whole deck.
     */
    public void requestLevel(String levelId, boolean restart) {
        connection.send(restart
                ? new RestartLevelC2S(levelId, currentWorld, List.of())
                : new ContinueLevelC2S(levelId, currentWorld));
    }

    /**
     * Starts a brand-new run straight into the level, with no pre-game screen.
     *
     * <p>Only for levels that have nothing to ask: a conveyor level deals its own cards,
     * so the seed chooser would offer a deck the server is about to discard. Because
     * there is no chooser, the level's opening dialogue has to play in game, and the
     * pending id is what carries that decision across the round trip.
     */
    private void requestFreshRunDirectly(String levelId, boolean restart) {
        directDialogueLevelId = levelId;
        connection.send(restart
                ? new RestartLevelC2S(levelId, currentWorld, List.of())
                : new ContinueLevelC2S(levelId, currentWorld));
    }

    /**
     * The level's opening dialogue, read from the local data packs.
     *
     * <p>Read locally for the same reason {@link #dealsItsOwnCards} and
     * {@link #lockedSlotsFor} are: the client loads the same packs as the server, and the
     * answer only decides what is drawn. An unknown level has no dialogue.
     */
    public List<DialogueLine> levelDialogue(String levelId) {
        Identifier id = Identifier.tryParse(levelId);
        LevelDef def = id == null ? null : BuiltInRegistries.LEVELS.get(id);
        return def == null ? List.of() : def.dialogue().lines();
    }

    /**
     * Asks the server to buy a locked level outright.
     *
     * <p>The price is deliberately not sent: the server reads it from the level's own
     * definition, so a modified client can choose which level to buy but not what it
     * costs. The refreshed level list comes back on its own.
     */
    public void buyLevelUnlock(String levelId) {
        connection.send(new UnlockLevelC2S(levelId, currentWorld));
    }

    /** Starts a level with an explicit seed selection and remembers it for next time. */
    public void startLevelWithSeeds(String levelId, boolean restart, List<String> selectedSeeds) {
        List<String> seeds = List.copyOf(selectedSeeds);
        rememberSeedSelection(currentWorld, levelId, seeds);
        connection.send(new PlayLevelC2S(levelId, currentWorld, restart, seeds));
    }

    /**
     * Opens the "Choose Your Seeds" screen before entering a level from the world map.
     *
     * <p>Only for levels that have no run to resume; see {@link #enterLevelFromMenu}. The
     * chooser is nested under whatever asked for it (the level setup screen), so its back
     * target pops.
     */
    public void openSeedSelection(LevelListS2C.LevelInfo info, boolean restart) {
        openSeedSelection(info, seedSelectionOrDefault(info.id(), info.seedPool(), info.maxSeedSlots()), null);
    }

    /**
     * The one decision for "play this level from a menu", shared by the level list and the
     * level setup screen.
     *
     * <p>A level with a resumable save is entered directly: the server loads the whole save
     * (board, entities, waves, wallet, card bar) and then asks continue/restart over it. It
     * must not be sent through the pre-game screens - the card bar and the team come from
     * the save, and asking the player to pick cards for a run that is about to be resumed is
     * what made the save prompt appear only <em>after</em> the seed chooser had been
     * submitted. Everything else goes through 关卡准备 and then the seed chooser as before.
     */
    public void enterLevelFromMenu(LevelListS2C.LevelInfo info) {
        if (info.hasRunningSave()) {
            requestLevel(info.id(), false);
        } else if (dealsItsOwnCards(info.id())) {
            // Nothing to choose: a conveyor level's cards are delivered by the level
            // itself, one at a time and for free, so a "choose your seeds" page would
            // offer a deck the server is going to discard.
            requestFreshRunDirectly(info.id(), false);
        } else {
            openSeedSelection(info, false);
        }
    }

    /**
     * True when this level deals its own cards - a conveyor belt, today - instead of the
     * player choosing a deck.
     *
     * <p>Read from the local level definition, like {@link #lockedSlotsFor}: the client
     * loads the same data packs, and the answer only decides which screens to open. What
     * the cards <em>are</em> still comes from the server. The question is asked of the
     * level's card-source mechanic rather than of a belt flag, so a second self-dealt card
     * source needs no change here.
     */
    public boolean dealsItsOwnCards(String levelId) {
        return com.pvzce.client.mechanic.ClientMechanics.dealsItsOwnCards(levelId);
    }

    /**
     * Opens seed selection with an explicit pre-filled list and optional custom
     * back behaviour. The save-prompt restart path uses this to keep the loaded
     * save frozen until the player either picks new cards or backs out.
     */
    public void openSeedSelection(LevelListS2C.LevelInfo info,
                                  List<String> initialSelection, Runnable onBack) {
        openScreen(createSeedSelection(info, initialSelection, onBack));
    }

    /**
     * The level's own cards, which the chooser pins in place.
     *
     * <p>They come from the level's definition, which the client has because it loads the
     * same data packs as the server. The server enforces them again when the level starts,
     * so a client that ignored this could not get out of them.
     */
    public List<String> lockedSlotsFor(String levelId) {
        Identifier id = Identifier.tryParse(levelId);
        LevelDef def = id == null ? null : BuiltInRegistries.LEVELS.get(id);
        return def == null ? List.of() : SeedOptions.lockedSlotIds(def);
    }

    /**
     * Builds the seed chooser for a level; the only place its arguments are assembled.
     *
     * <p>Whether this is a restart is derived from the back behaviour rather than passed
     * beside it: the two were always set together (the save prompt's 重新开始 is the only
     * restart that goes through the chooser, and it is also the only one that keeps a state
     * to return to), and stating one fact twice is how the two drift.
     */
    private ChooseSeedsScreen createSeedSelection(LevelListS2C.LevelInfo info,
                                                  List<String> initialSelection, Runnable onBack) {
        return new ChooseSeedsScreen(this, info.id(), info.name(), info.seedPool(),
                info.maxSeedSlots(), info.previewZombies(), info.width(), info.height(),
                info.sceneCells(), initialSelection, onBack != null, onBack, lockedSlotsFor(info.id()));
    }

    private LevelListS2C.LevelInfo findLevelInfo(String levelId) {
        for (LevelListS2C.LevelInfo info : levelList) {
            if (info.id().equals(levelId)) {
                return info;
            }
        }
        return null;
    }

    /**
     * True when this level's chooser would have nothing to offer.
     *
     * <p>Read from the level list's own snapshot - the pool the server sent and the level's
     * own cards - so the answer matches the screen that would have been built from it. Used
     * by the save prompt's restart, which skips a page whose only button would be "start".
     */
    public boolean hasNothingToChoose(LevelListS2C.LevelInfo info) {
        return info != null && com.pvzce.common.core.SeedOptions.hasNothingToChoose(
                info.seedPool(), info.maxSeedSlots(), lockedSlotsFor(info.id()));
    }

    /**
     * The save prompt's "重新开始" option: keep the loaded save untouched and
     * paused while the client shows seed selection. Submitting the new cards
     * sends {@code PlayLevelC2S(restart=true)}, which deletes the old save and
     * creates the fresh level with the chosen bar. Backing out re-opens the
     * prompt so the player can still continue.
     */
    public void openSeedSelectionForRestart(LevelSavePromptS2C prompt) {
        LevelListS2C.LevelInfo info = findLevelInfo(prompt.levelId());
        if (info == null || dealsItsOwnCards(prompt.levelId()) || hasNothingToChoose(info)) {
            // No registry snapshot (for example a direct smoke request), nothing to choose
            // because the level deals its own cards, or nothing to choose because the level
            // pins every slot: fall back to the server-side restart, which is the same thing
            // minus a page asking for a deck that is not the player's to pick.
            connection.send(new RestartLevelC2S(prompt.levelId(), prompt.worldName(), List.of()));
            return;
        }
        List<String> initial = prompt.levelId().equals(level.levelId())
                ? level.slots().stream().map(slot -> slot.defId()).toList()
                : seedSelectionOrDefault(info.id(), info.seedPool(), info.maxSeedSlots());
        // The old run is still loaded (and frozen) behind the chooser, so its music
        // would otherwise keep playing under the chooser theme until the seed
        // selection finally replaced the level. Only the client stops here: the
        // server is deliberately not told to leave, because backing out returns to
        // the save prompt over the same instance.
        silenceLevelMusic();
        openSeedSelection(info, initial, () -> {
            // Back to the frozen run the chooser is covering, so the prompt can be asked
            // again over it.
            popScreen();
            // Backing out returns to a level that is still loaded, so give it a theme
            // again. The exact track is not restored - music state is not part of a
            // level's saved snapshot (see docs/当前项目架构.md, known gaps).
            if (music != null) {
                music.startLevel(com.pvzce.common.PvzceSounds.MUSIC_GRASSWALK.toString());
            }
            showLevelSavePrompt(prompt);
        });
    }

    /** Stops the level tracks locally, without telling the server to close the level. */
    private void silenceLevelMusic() {
        if (music != null) {
            music.leaveLevel();
        }
    }

    private List<String> seedSelectionOrDefault(String levelId, List<SeedOption> pool, int maxSeedSlots) {
        List<String> remembered = seedSelection(currentWorld, levelId);
        if (remembered != null) {
            return remembered;
        }
        int limit = Math.max(0, maxSeedSlots);
        List<String> defaults = new ArrayList<>();
        for (SeedOption option : pool) {
            if (defaults.size() >= limit) {
                break;
            }
            defaults.add(option.slotId());
        }
        return defaults;
    }

    /** Returns the remembered selection, or {@code null} when this world/level has never been played. */
    public List<String> seedSelection(String world, String levelId) {
        List<String> selection = rememberedSeeds.get(seedKey(world, levelId));
        return selection == null ? null : List.copyOf(selection);
    }

    private void rememberSeedSelection(String world, String levelId, List<String> seeds) {
        rememberedSeeds.put(seedKey(world, levelId), List.copyOf(seeds));
        saveSeedSelections();
    }

    private static String seedKey(String world, String levelId) {
        return (world == null ? "world" : world) + "|" + levelId;
    }

    private void loadSeedSelections() {
        seedSelectionFile = gameDir.resolve("config/pvzce-seed-selections.json");
        rememberedSeeds.clear();
        if (!Files.isRegularFile(seedSelectionFile)) {
            return;
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(seedSelectionFile));
            if (!parsed.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                if (!entry.getValue().isJsonArray()) {
                    continue;
                }
                List<String> seeds = new ArrayList<>();
                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    if (element.isJsonPrimitive()) {
                        seeds.add(element.getAsString());
                    }
                }
                rememberedSeeds.put(entry.getKey(), List.copyOf(seeds));
            }
        } catch (Throwable t) {
            System.err.println("Failed to read seed selections: " + t.getMessage());
        }
    }

    private void saveSeedSelections() {
        if (seedSelectionFile == null) {
            return;
        }
        try {
            Files.createDirectories(seedSelectionFile.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<String, List<String>> entry : rememberedSeeds.entrySet()) {
                JsonArray array = new JsonArray();
                for (String seed : entry.getValue()) {
                    array.add(seed);
                }
                root.add(entry.getKey(), array);
            }
            Files.writeString(seedSelectionFile, root.toString());
        } catch (Throwable t) {
            System.err.println("Failed to write seed selections: " + t.getMessage());
        }
    }

    /** Server found a resumable save; show the continue/restart dialog over the loaded world. */
    public void showLevelSavePrompt(LevelSavePromptS2C prompt) {
        if (!(currentScreen() instanceof InGameScreen)) {
            deferredSavePrompt = prompt;
            return;
        }
        if (savePromptOpen) {
            return;
        }
        savePromptOpen = true;
        LevelSaveDialog dialog = LevelSaveDialog.create(this, prompt,
                () -> {
                    connection.send(new ContinueLevelC2S(prompt.levelId(), prompt.worldName()));
                },
                () -> {
                    openSeedSelectionForRestart(prompt);
                });
        dialog.closeOnEscape(false);
        dialog.onClose(() -> savePromptOpen = false);
        currentScreen().showDialog(dialog);
    }

    public void leaveLevel() {
        connection.send(new LeaveLevelC2S());
        clearLevelClientState();
        showWorldSelect();
    }

    /**
     * Drops every piece of per-level client state. Shared by "leave to the menu" and
     * "restart the level" so the two cannot drift; the old restart path cleared none
     * of it because it never closed the level in the first place.
     */
    private void clearLevelClientState() {
        savePromptOpen = false;
        deferredSavePrompt = null;
        directDialogueLevelId = null;
        if (music != null) {
            music.leaveLevel();
        }
        particles.clear();
        liquidRipples.clear();
        if (animations != null) {
            animations.clear();
        }
        level.reset();
    }

    /**
     * The pause menu's 重新开始.
     *
     * <p>The old implementation pushed the seed chooser <em>on top of</em> the running
     * level and left that level alive: backing out with ESC returned to the old
     * level's pause menu, so "restart" never actually closed anything. Now the current
     * level is genuinely closed first (the server writes a save on the way out, so
     * backing out of the chooser still leaves the run resumable from the level list)
     * and the seed chooser is entered exactly like a normal level entry - a screen
     * replacement, with 返回/ESC leading to the level list rather than back into the
     * level that was just closed.
     */
    public void restartCurrentLevel() {
        String levelId = level.levelId();
        LevelListS2C.LevelInfo info = findLevelInfo(levelId);
        if (info == null) {
            // No registry snapshot for this level (editor/smoke entry): fall back to a
            // plain server-side restart, which still closes the old instance. There is no
            // chooser on this path either, so the opening dialogue plays in game.
            requestFreshRunDirectly(levelId, true);
            return;
        }
        connection.send(new LeaveLevelC2S());
        clearLevelClientState();
        if (dealsItsOwnCards(levelId)) {
            // A conveyor level restarts straight into a fresh belt: there are no cards to
            // pick, so the chooser that normally sits between "closed" and "restarted" has
            // nothing to ask. The init packet rebuilds the screen, as it does for any entry.
            requestFreshRunDirectly(levelId, true);
            return;
        }
        List<String> initial = seedSelectionOrDefault(info.id(), info.seedPool(), info.maxSeedSlots());
        // Installed as the root (the level was just closed, so nothing is underneath), which
        // is also what makes the chooser a restart: onBack non-null is the one thing that
        // says "there is a state to come back to".
        setScreenReplacing(createSeedSelection(info, initial, this::showLevelList));
    }

    /**
     * The running level's display name, for the HUD's progress meter.
     *
     * <p>Comes from the cached level list rather than the level init packet, which
     * carries only the id; a level opened without a list (a smoke request) falls back to
     * the id's last path segment, which is what the list itself would have shown.
     */
    public String currentLevelName() {
        String id = level.levelId();
        if (id == null || id.isEmpty()) {
            return "";
        }
        LevelListS2C.LevelInfo info = findLevelInfo(id);
        if (info != null && info.name() != null && !info.name().isBlank()) {
            return info.name();
        }
        int slash = Math.max(id.lastIndexOf('/'), id.indexOf(':'));
        return slash >= 0 && slash + 1 < id.length() ? id.substring(slash + 1) : id;
    }

    /** Backs out of a level-entry flow to the level list of the current world. */
    public void showLevelList() {
        setScreenReplacing(new LevelSelectScreen(this));
    }

    // ---------- accessors ----------

    public Connection connection() {
        return connection;
    }

    public PvzceWindow window() {
        return window;
    }

    public PvzceResourceManager resources() {
        return resources;
    }

    public TextureManager textures() {
        return textures;
    }

    public FontRenderer font() {
        return font;
    }

    public SoundEngine sound() {
        return sound;
    }

    public AnimationManager animations() {
        return animations;
    }

    public PvzceMusicController music() {
        return music;
    }

    public void onMusicEvent(String track, String event, boolean loop, boolean stop, float volume, float fadeSeconds) {
        if (music != null) {
            music.playCue(track, event, loop, stop, volume, fadeSeconds);
        }
    }

    public void onGameState(String state, String winTeamId) {
        if (music == null || state == null || state.equals("running")) {
            return;
        }
        boolean plantSideWon = winTeamId != null && winTeamId.contains("plant");
        // A plant win does not play its stinger here: the reward packet that follows
        // lands a seed packet or money bag on the lawn, and the victory music belongs
        // to the moment the player claims it (InGameScreen.tickReward). A defeat has
        // nothing to claim, so it plays immediately.
        if (plantSideWon && currentScreen() instanceof InGameScreen) {
            return;
        }
        music.playWinLose(!(winTeamId != null && winTeamId.contains("zombie")));
    }

    /**
     * The run's payout: handed to the in-game screen, which drops it on the lawn.
     *
     * <p>Arrives one packet after {@link #onGameState}, so the level is already frozen
     * and the in-game screen is still up. The award page is deliberately <em>not</em>
     * opened here - the player claims the reward first (see
     * {@code InGameScreen.showReward}), and the page follows the claim. A reward that
     * arrives while some other screen is up (a smoke run, a resumed level) is dropped
     * rather than stacked on an unrelated screen.
     */
    public void onLevelReward(LevelRewardS2C reward) {
        if (reward == null || !(currentScreen() instanceof InGameScreen screen)) {
            return;
        }
        if (!level.gameState().equals("running") && level.winTeam().contains("plant")) {
            screen.showReward(reward);
        }
    }

    /** Opens the award page; called by the in-game screen once the reward is claimed. */
    public void openAwardScreen(LevelRewardS2C reward) {
        if (reward != null) {
            openScreen(new com.pvzce.client.gui.screens.AwardScreen(this, reward));
        }
    }

    /**
     * Leaves a finished level and goes back to the level list it came from.
     *
     * <p>{@link #leaveLevel()} lands on the world list, which is a step further back
     * than a player who just finished a level wants: the next thing they do is pick
     * the next level, not the next world. The server already refreshed the list when
     * it paid out, so the finished level's row is current.
     */
    public void finishLevelAndShowList() {
        connection.send(new LeaveLevelC2S());
        clearLevelClientState();
        showLevelList();
    }

    public PvzceClientConfig config() {
        return config;
    }

    // ---------- video settings ----------

    public void setFullscreen(boolean fullscreen) {
        boolean applied = window.setFullscreen(fullscreen);
        config.setFullscreen(applied);
        config.save();
        refreshGui();
    }

    public void setVsync(boolean vsync) {
        window.setVsync(vsync);
        config.setVsync(vsync);
        config.save();
    }

    public void setMaxFps(int maxFps) {
        config.setMaxFps(maxFps);
        config.save();
    }

    public void setWindowResolution(int width, int height) {
        window.setResolution(width, height);
        config.setWindowSize(width, height);
        config.save();
        refreshGui();
    }

    public void setGuiScaleSetting(int guiScale) {
        config.setGuiScale(guiScale);
        config.save();
        refreshGui();
    }

    /**
     * Sets the water quality tier.
     *
     * <p>No GL state has to be touched: the tier only selects which terms the liquid
     * shader evaluates, and the pass reads it from the config every frame. It is
     * deliberately independent of {@code shadersEnabled}, which replaces the whole
     * pass with a flat fallback.
     */
    public void setWaterQuality(int quality) {
        config.setWaterQuality(quality);
        config.save();
    }

    public void setShadersEnabled(boolean enabled) {
        config.setShadersEnabled(enabled);
        // Applied immediately as well as at the next world view, so the toggle takes
        // effect on the board the player is looking at (and on the seed chooser, which
        // never enters a world view at all).
        RenderSystem.setShaderEffectsEnabled(enabled);
        config.save();
    }

    /** Rebuilds all open screens and the overlay after a resolution/scale change. */
    public void refreshGui() {
        screens.resizeAll();
        if (overlay != null) {
            overlay.onResize();
        }
    }

    public float currentConfigValue(String id) {
        if (currentScreen() instanceof ConfigScreen configScreen) {
            return configScreen.valueOf(id);
        }
        return 0F;
    }

    /** Volume settings built through the public ConfigBuilder API. */
    public ConfigScreen buildSettingsConfig() {
        ConfigBuilder builder = ConfigBuilder.create(this).setTitle("音量设置");
        builder.setSavingRunnable(() -> config.save());
        ConfigCategory volume = builder.getOrCreateCategory("音量");
        volume.addEntry(ConfigEntryBuilder.createFloat("master", "主音量",
                config.masterVolume(), 0F, 1F, () -> {
                    config.setMasterVolume(currentConfigValue("master"));
                    sound.setMasterVolume(config.masterVolume());
                }));
        volume.addEntry(ConfigEntryBuilder.createFloat("music", "音乐",
                config.musicVolume(), 0F, 1F, () -> {
                    config.setMusicVolume(currentConfigValue("music"));
                    sound.setMusicVolume(config.musicVolume());
                    music.onVolumeChanged();
                }));
        volume.addEntry(ConfigEntryBuilder.createFloat("sfx", "音效",
                config.sfxVolume(), 0F, 1F, () -> {
                    config.setSfxVolume(currentConfigValue("sfx"));
                    sound.setSfxVolume(config.sfxVolume());
                }));
        return builder.build();
    }

    public ParticleEngine particles() {
        return particles;
    }

    public ClientLevel level() {
        return level;
    }

    public Path gameDir() {
        return gameDir;
    }

    public String currentWorld() {
        return currentWorld;
    }

    public void setCurrentWorld(String currentWorld) {
        // A level list describes exactly one world - its 进行中/已通关 labels are the save
        // files of that world. Carrying the previous world's snapshot into the new one showed
        // those labels for the wrong world (and they drive whether entering a level loads a
        // save or opens the seed chooser), so drop it and let the level list refetch.
        if (!java.util.Objects.equals(this.currentWorld, currentWorld)) {
            levelList = List.of();
            // The profile is per world too: keeping the previous world's coins on
            // screen while the new world's list loads would show another world's money.
            profile.reset();
        }
        this.currentWorld = currentWorld;
    }

    public List<LevelListS2C.LevelInfo> levelList() {
        return levelList;
    }

    /** The current world's coins and unlocks, as the server last described them. */
    public ClientProfile profile() {
        return profile;
    }

    public void setProfile(int coins, List<String> unlocked, boolean unlockAll) {
        profile.apply(coins, unlocked, unlockAll);
    }

    /** As above, with the backpack's card-slot count the server reports. */
    public void setProfile(int coins, List<String> unlocked, boolean unlockAll, int seedSlots) {
        profile.apply(coins, unlocked, unlockAll, seedSlots);
    }

    /**
     * The level select screen's tab table, as the server last described it.
     *
     * <p>Kept beside the level list rather than inside the screen because the editor needs
     * the same table to offer "move this level to another theme/category" - and the two
     * must agree on what a valid page is.
     */
    public List<LevelTabsS2C.Tab> levelTabs() {
        return levelTabs;
    }

    public void setLevelTabs(List<LevelTabsS2C.Tab> tabs) {
        this.levelTabs = tabs == null ? List.of() : List.copyOf(tabs);
    }

    public void setLevelList(List<LevelListS2C.LevelInfo> levelList) {
        this.levelList = levelList == null ? List.of() : List.copyOf(levelList);
        if (!smokeSeedLevel.isBlank() && !smokeSeedOpened) {
            for (LevelListS2C.LevelInfo info : this.levelList) {
                if (smokeSeedLevel.equals(info.id())) {
                    smokeSeedOpened = true;
                    openSeedSelection(info, false);
                    break;
                }
            }
        }
        // A test run saved the level and asked the server for a reload; the list that
        // arrives next carries the saved definition, so this is where its seed chooser can
        // open with current data.
        if (pendingTestLevelId != null) {
            String wanted = pendingTestLevelId;
            pendingTestLevelId = null;
            for (LevelListS2C.LevelInfo info : this.levelList) {
                if (wanted.equals(info.id())) {
                    if (dealsItsOwnCards(info.id())) {
                        // A conveyor level hands out its own cards, so testing it means
                        // starting it, not picking a deck for it.
                        requestFreshRunDirectly(info.id(), true);
                    } else {
                        openSeedSelection(info, true);
                    }
                    break;
                }
            }
        }
    }

    /**
     * Plays the level the editor just saved, through the same flow as playing it from the
     * level list: save, reload, then choose seeds.
     *
     * <p>The editor's test button used to call {@link #requestLevel} directly, which asks the
     * server to start the level - so testing skipped the seed chooser that opening the same
     * level from the list shows. Both paths now go through {@link #openSeedSelection}.
     */
    public void testEditedLevel(String levelId) {
        if (levelId == null) {
            return;
        }
        pendingTestLevelId = levelId;
        connection.send(new CommandC2S("/reload"));
    }

    public long clientTick() {
        return clientTick;
    }

    public int fps() {
        return fps;
    }

    /**
     * Writes the framebuffer to a PNG with the top row first.
     *
     * <p>{@code glReadPixels} returns rows bottom-up while PNG stores them top-down, so
     * exactly one flip is needed and this did two - it copied the rows in reverse into
     * a second buffer <em>and</em> set {@code stbi_flip_vertically_on_write} - so every
     * smoke screenshot came out upside down and had to be flipped again by hand before
     * it could be read. The manual copy is the one that stays: STB's flag is global
     * state on the writer rather than a per-call argument, so leaving it set would
     * affect any later write too.
     */
    private void capture(String path) {
        int w = window.width();
        int h = window.height();
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
            System.err.println("[SMOKE] failed to write screenshot " + path);
        }
    }
}
