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
import com.pvzce.client.gui.screens.ChooseSeedsScreen;
import com.pvzce.client.gui.screens.EditorScreen;
import com.pvzce.client.gui.screens.InGameScreen;
import com.pvzce.client.gui.screens.RoundClearDialog;
import com.pvzce.client.gui.screens.LevelSaveDialog;
import com.pvzce.client.gui.screens.LevelSelectScreen;
import com.pvzce.client.gui.screens.TitleScreen;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.client.particle.ParticleEngine;
import com.pvzce.client.renderer.LevelStage;
import com.pvzce.client.renderer.Matrix4f;
import com.pvzce.client.renderer.PvzceCamera;
import com.pvzce.client.renderer.RenderSystem;
import com.pvzce.client.renderer.ShaderProgram;
import com.pvzce.client.renderer.TextureUv;
import com.pvzce.client.renderer.TimeOfDayLighting;
import com.pvzce.client.renderer.SpriteRenderer;
import com.pvzce.client.renderer.font.Fonts;
import com.pvzce.client.renderer.sprite.Sprite;
import com.pvzce.client.renderer.texture.TextureManager;
import com.pvzce.client.sound.PvzceMusicController;
import com.pvzce.client.sound.SoundEngine;
import com.pvzce.common.network.Connection;
import com.pvzce.common.network.PvzcePackets;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SeedOptions;
import com.pvzce.api.content.LevelDef;
import com.pvzce.common.network.packet.LevelListS2C;
import com.pvzce.common.network.packet.LevelRewardS2C;
import com.pvzce.common.network.packet.LevelSavePromptS2C;
import com.pvzce.common.network.packet.LevelTabsS2C;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.network.packet.LeaveLevelC2S;
import com.pvzce.common.network.packet.ContinueLevelC2S;
import com.pvzce.common.network.packet.UnlockLevelC2S;
import com.pvzce.common.network.packet.PlayLevelC2S;
import com.pvzce.common.network.packet.ReloadPacksC2S;
import com.pvzce.common.network.packet.RestartLevelC2S;
import com.pvzce.common.resource.PvzceResourceManager;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.common.util.MathUtil;
import com.google.gson.JsonElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
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
    private Fonts fonts;
    /**
     * The last frame, kept small for a screen that wants the view it was opened over as a blurred
     * backdrop - see {@link com.pvzce.client.renderer.BlurredBackdrop}.
     */
    private final com.pvzce.client.renderer.BlurredBackdrop blurredBackdrop =
            new com.pvzce.client.renderer.BlurredBackdrop();
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

    /**
     * Who this session is playing as, and therefore which save the menus read.
     *
     * <p>Seeded from the config in the constructor rather than left at the default: a world is
     * one player's save, and asking "who is playing?" on every start is a question the player
     * answered the last time they played. The menu still shows the name, and changing it is one
     * click away on the title screen's player board.
     */
    private String currentWorld = PvzceClientConfig.DEFAULT_WORLD;
    private volatile List<LevelListS2C.LevelInfo> levelList = List.of();
    private volatile List<LevelTabsS2C.Tab> levelTabs = List.of();
    /** Coins and unlocks of the current world; menus only, the server is authoritative. */
    private final ClientProfile profile = new ClientProfile();
    private long clientTick;
    private int lastWindowWidth;
    private int lastWindowHeight;
    /**
     * The {@code pvzce.smoke*} development harness; see {@link SmokeDriver} and the smoke guide.
     *
     * <p>Constructed with no properties set it is inert: the title screen opens and every hook
     * returns immediately.
     */
    private final SmokeDriver smoke = new SmokeDriver(this);
    /** Set while waiting for the level list that follows a test-run reload. */
    private String pendingTestLevelId;
    private long backspaceNextNanos;
    private boolean backspaceHeld;
    private boolean leftMouseWasDown;
    private char suppressNextChar;
    private boolean debugOverlayEnabled;
    /** Set from {@code pvzce.dumpFontAtlas}: where to write the glyph atlases, once. */
    private java.nio.file.Path dumpFontAtlasTo;
    private boolean savePromptOpen;
    private LevelSavePromptS2C deferredSavePrompt;
    /** A round-clear dialog that arrived while the client was not on the board. */
    private com.pvzce.common.network.packet.RoundClearS2C deferredRoundClear;
    private boolean roundClearOpen;
    /** The dialog currently on screen, so a round that moved on can close it. */
    private RoundClearDialog openRoundClear;
    /** A round chooser that is waiting for the level list to arrive. */
    private PendingRoundChooser pendingRoundChooser;
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
    /** How long a menu keeps showing the last line the server pushed. */
    private static final long MESSAGE_VISIBLE_NANOS =
            5L * PvzceConstants.TICKS_PER_SECOND * PvzceConstants.NANOS_PER_TICK;
    /** The last line the server pushed, and when it arrived; see {@link #recentServerMessage()}. */
    private String lastServerMessage = "";
    private long lastServerMessageNanos;
    private long serverMessageCount;
    /** A pack reload this client asked for and has not seen answered yet. */
    private boolean packReloadPending;
    private long packReloadBaseline;
    private int contentReloads;

    public PvzceClient(Connection connection, Path gameDir, ClassLoader classLoader) {
        this.connection = connection;
        this.gameDir = gameDir;
        this.classLoader = classLoader;
        // Settings are read here rather than in run(): the window is built from them, and a
        // windowless client (tests, tooling) has to be able to ask for a preference without
        // running the render loop. `load` writes the file out when there is none, so this
        // also means a constructed client always has a config directory.
        this.config = PvzceClientConfig.load(gameDir);
        String dumpAtlas = System.getProperty("pvzce.dumpFontAtlas");
        if (dumpAtlas != null && !dumpAtlas.isBlank()) {
            this.dumpFontAtlasTo = java.nio.file.Path.of(dumpAtlas);
        }
        // The remembered player, from the same file: who is playing is a fact about this game
        // directory, not about this process, so it survives a restart.
        this.currentWorld = config.lastWorld();
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
        reloadContent();

        window = new PvzceWindow("PVZ Community Edition", config);
        lastWindowWidth = window.width();
        lastWindowHeight = window.height();
        RenderSystem.init();
        // Seed the shader gate from the config as well as setting it in
        // beginWorldView, because the boards that render OUTSIDE a running level -
        // the seed chooser's preview and the editor's canvas - never call
        // beginWorldView, and without this they would take the shader path even
        // when the player has turned shaders off.
        RenderSystem.setShaderEffectsEnabled(shadersEnabled());
        textures = new TextureManager(resources);
        // The text renderer reads the GUI scale per draw rather than once at startup:
        // glyphs are rasterised at the window's actual pixel density, so resizing the
        // window or changing the GUI scale re-rasterises sharp text instead of
        // stretching a glyph atlas that was baked at one size.
        fonts = new Fonts(resources, this::guiScale);
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

        smoke.applyInitialScreen();

        long nextFrameNanos = System.nanoTime();
        while (!window.shouldClose()) {
            window.pollEvents();
            clientTick++;

            connection.tick();

        smoke.beforeFrame();

            pollInput();
            Screen screen = currentScreen();
            screen.initIfNeeded();
            long beforeTick = System.nanoTime();
            screen.tick();
            music.tick();
            animations.tick();
            smoke.addTickNanos(System.nanoTime() - beforeTick);
            long beforeRender = System.nanoTime();
            render();
            smoke.addRenderNanos(System.nanoTime() - beforeRender);
            smoke.sampleFrame();

            if (smoke.afterFrame(screen)) {
                break;
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
        blurredBackdrop.close();
        window.close();
    }

    // ---------- content ----------

    /**
     * Rebuilds everything read out of the pack stack: the stack itself, then every cache that
     * was filled from it.
     *
     * <p>Once at startup, then again whenever the packs change (see {@link #requestPackReload()}).
     * Every cache here had to be listed by hand when the pack list became editable, because each
     * one is keyed by id or path and never revalidated - which is exactly what makes it fast and
     * what would keep a replaced texture, glyph, animation, liquid rule, display name or sound
     * playing for the rest of the session.
     *
     * @return whether the stack was rescanned; a failure is logged and counted too, so a screen
     *         waiting on the reload does not wait forever
     */
    public boolean reloadContent() {
        if (resources == null) {
            // A windowless client (tests, tooling) never loaded a stack, so there is nothing to
            // rebuild; counting it keeps "waiting for the reload to land" from waiting forever.
            contentReloads++;
            return true;
        }
        boolean reloaded = true;
        try {
            resources.reload();
        } catch (IOException e) {
            // The stack is left holding whatever the rescan managed to build rather than
            // nothing at all: a page that cannot list packs still draws the built-in content.
            LOGGER.error("Reloading the pack stack failed", e);
            reloaded = false;
        }
        // Animation files are parsed lazily and cached; a reload must drop both the
        // parsed files and the "missing/broken" marks, otherwise a fixed animation
        // stays invisible for the rest of the session.
        if (animations != null) {
            animations.invalidate();
        }
        // A pack can replace a bundled TTF. Glyphs are rasterised from the file and
        // cached, so a reload has to drop them or the old outlines stay on screen for
        // the rest of the session.
        if (fonts != null) {
            fonts.invalidate();
        }
        // Same reason as the animations above: which scene elements are liquid is
        // cached by id, so a reload that changes a definition has to drop the cache
        // or the old answer would be used for the rest of the session.
        com.pvzce.client.renderer.liquid.LiquidTextures.invalidate();
        // The two remaining id-keyed caches, for the same reason as the three above.
        if (textures != null) {
            textures.invalidate();
        }
        if (sound != null) {
            sound.invalidate();
        }
        // Display names come from the pack stack too, so a pack that adds or renames
        // content updates the editor's palette on the same reload as the content.
        com.pvzce.client.gui.GuiLang.reload(resources);
        try {
            var tagResult = PvzceTags.MANAGER.reload(resources, BuiltInRegistries.ACCESS);
            for (String error : tagResult.errors()) {
                // The client used to discard this result entirely, so a broken tag file
                // was silent on the client and reported only on the server.
                LOGGER.warn("[tags] {}", error);
            }
        } catch (IOException e) {
            LOGGER.error("Reloading tags failed", e);
            reloaded = false;
        }
        contentReloads++;
        return reloaded;
    }

    /** How many times the pack stack has been rebuilt; a screen watches it to see its request land. */
    public int contentReloads() {
        return contentReloads;
    }

    /**
     * Tells the server the pack list changed, and rebuilds this side once the server answers.
     *
     * <p>The order is the point: the level definitions and content registries travel from the
     * server to the client, so a client that reloaded first would parse the new resources against
     * the old registries. The answer is the {@code ServerMessageS2C} the server's own reload
     * sends - the single-player server is in-process, so there is no other reply to wait for.
     */
    public void requestPackReload() {
        packReloadPending = true;
        packReloadBaseline = serverMessageCount;
        connection.send(new ReloadPacksC2S());
    }

    /**
     * Records a line the server pushed.
     *
     * <p>Kept on the client rather than only in {@code ClientLevel} because a refusal can arrive
     * while the player is in a menu, where nothing was reading the level's message list - so the
     * answer to a click was invisible until they entered a level.
     */
    public void onServerMessage(String message) {
        lastServerMessage = message == null ? "" : message;
        lastServerMessageNanos = System.nanoTime();
        serverMessageCount++;
        if (packReloadPending && serverMessageCount > packReloadBaseline) {
            packReloadPending = false;
            reloadContent();
        }
    }

    /** The last server line while it is still fresh, otherwise an empty string. */
    public String recentServerMessage() {
        return System.nanoTime() - lastServerMessageNanos <= MESSAGE_VISIBLE_NANOS
                ? lastServerMessage : "";
    }

    /**
     * How many lines the server has pushed this session.
     *
     * <p>A screen that sent a request and is waiting for its answer compares this before and
     * after; "is there a fresh message" cannot tell a line that was already there from the reply.
     */
    public long serverMessageCount() {
        return serverMessageCount;
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
                if (debugOverlayEnabled && fonts != null) {
                    LOGGER.info("[fonts]\n{}", fonts.debugInfo());
                }
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
    void openConsole(String initialContents) {
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
    void deliverGuiClick(double guiX, double guiY, int button) {
        if (overlay != null) {
            overlay.mouseClicked(guiX, guiY, button);
        } else {
            currentScreen().dispatchMouseClicked(guiX, guiY, button);
        }
    }

    /** A click in raw framebuffer coordinates, to whichever layer owns the mouse. */
    void deliverRawClick(double rawX, double rawY, int button) {
        if (overlay != null) {
            overlay.mouseClicked(guiMouseX(rawX), guiMouseY(rawY), button);
        } else {
            currentScreen().mouseClicked(rawX, rawY, button);
        }
    }

    void deliverRawDrag(double rawX, double rawY, int button) {
        if (overlay != null) {
            overlay.mouseDragged(guiMouseX(rawX), guiMouseY(rawY), button);
        } else {
            currentScreen().mouseDragged(rawX, rawY, button);
        }
    }

    void deliverRawRelease(double rawX, double rawY, int button) {
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
        // One-shot atlas dump: the glyph atlas is uploaded from memory rather than
        // decoded from a PNG, so when text renders wrong there is otherwise no file to
        // look at. `-Dpvzce.dumpFontAtlas=<dir>` writes it after the first drawn frame.
        if (dumpFontAtlasTo != null) {
            fonts.dumpAtlases(dumpFontAtlasTo);
            LOGGER.info("[fonts] atlases written to {}", dumpFontAtlasTo);
            dumpFontAtlasTo = null;
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
        RenderSystem.setShaderEffectsEnabled(shadersEnabled());
        if (shadersEnabled()) {
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
        RenderSystem.setOverlayShader();
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
            float r = MathUtil.lerp(1F, 0.72F, nightBlend);
            float g = MathUtil.lerp(0.85F, 0.82F, nightBlend);
            float b = MathUtil.lerp(0.35F, 1F, nightBlend);
            // Brighter at night than by day, which is the opposite of how this started and
            // the point of the number: the lawn at noon is already fully lit, so a sun lying
            // on it only washed a square of grass towards white - the glare the player read
            // as "the sun is too bright" - while at night the same glow is the one warm
            // thing on the board. Presentation only; nothing about collection changes.
            float strength = MathUtil.lerp(SUN_LIGHT_DAY, SUN_LIGHT_NIGHT, nightBlend);
            RenderSystem.setPointLight(i, sun.cellX(), sun.cellY() + sun.height(),
                    SUN_LIGHT_RADIUS, r, g, b, strength);
        }
    }

    /**
     * How strongly a sun drop lights the grass around it, by day and by night.
     *
     * <p>Both are guesses that were tuned against a screenshot, which is why they live
     * together with their own names: the day value is "bright enough to notice you dropped
     * something, dim enough not to bleach the cell it landed on", and the night value is
     * "still reads as a light source after the scene tint has taken most of the colour out
     * of the lawn".
     */
    private static final float SUN_LIGHT_DAY = 0.16F;
    private static final float SUN_LIGHT_NIGHT = 0.30F;
    /** How far a sun drop's glow reaches, in cells. */
    private static final float SUN_LIGHT_RADIUS = 2.2F;

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
        TimeOfDayLighting.Lighting lighting = TimeOfDayLighting.compute(
                level.smoothDayTicks(), time.dayLength(), time.nightLength(),
                level.width(), level.height());
        worldLightX = lighting.sunX();
        worldLightY = lighting.sunY();
        worldTintR = lighting.tintR();
        worldTintG = lighting.tintG();
        worldTintB = lighting.tintB();
        worldTintLift = lighting.lift();
        worldLightR = lighting.sunR();
        worldLightG = lighting.sunG();
        worldLightB = lighting.sunB();
        worldLightStrength = lighting.strength();
        worldNightBlend = lighting.nightBlend();
        RenderSystem.setTimeOfDay(lighting.tintR(), lighting.tintG(), lighting.tintB(), lighting.lift(),
                lighting.sunX(), lighting.sunY(), lighting.sunRadius(),
                lighting.sunR(), lighting.sunG(), lighting.sunB(), lighting.strength());
    }

    /**
     * The lighting a level <em>will open with</em>, for a screen that draws its board before
     * the level exists.
     *
     * <p>The seed chooser is the caller: it shows the lawn the player is about to play on, and
     * it has no clock - no {@code LevelInitS2C} has arrived yet - so a night level used to be
     * previewed in broad daylight and then turn dark the moment it started. The level's own
     * file is what the client has instead, read locally the same way the chooser already reads
     * {@code dealsItsOwnCards} and the seed pool, and tick zero is where a fresh run starts.
     *
     * <p>{@code x}/{@code y}/{@code cellWidth}/{@code cellHeight} are the board's rectangle in
     * the caller's own pixels, so the moon glow lands on the lawn the caller drew rather than
     * on the lawn's world coordinates. Null - an unknown level - leaves the shader neutral.
     *
     * <p>Static, and separate from {@link #applyLevelLighting}, because it is the half worth a
     * test: it reads the packs and does the arithmetic, and needs no window to do either.
     */
    public static TimeOfDayLighting.Lighting levelLighting(String levelId, float x, float y,
                                                           float cellWidth, float cellHeight) {
        Identifier id = Identifier.tryParse(levelId);
        LevelDef def = id == null ? null : BuiltInRegistries.LEVELS.get(id);
        if (def == null) {
            return null;
        }
        return TimeOfDayLighting.inRect(TimeOfDayLighting.compute(0F,
                        ruleInt(def, PvzceIds.RULE_DAY_LENGTH, 0),
                        ruleInt(def, PvzceIds.RULE_NIGHT_LENGTH, -1),
                        def.width(), def.height()),
                x, y, cellWidth, cellHeight);
    }

    /** Applies {@link #levelLighting}; an unknown level leaves the shader neutral. */
    public void applyLevelLighting(String levelId, float x, float y,
                                   float cellWidth, float cellHeight) {
        TimeOfDayLighting.Lighting lighting = levelLighting(levelId, x, y, cellWidth, cellHeight);
        if (lighting == null) {
            RenderSystem.setGuiShader();
            return;
        }
        applyLighting(lighting);
    }

    /** Pushes one computed lighting into the shader. */
    public static void applyLighting(TimeOfDayLighting.Lighting lighting) {
        RenderSystem.setTimeOfDay(lighting.tintR(), lighting.tintG(), lighting.tintB(), lighting.lift(),
                lighting.sunX(), lighting.sunY(), lighting.sunRadius(),
                lighting.sunR(), lighting.sunG(), lighting.sunB(), lighting.strength());
    }

    /**
     * One of a level's rules as an int, with the registry's own default when the file is silent.
     *
     * <p>Read locally for the same reason the seed pool is: the client parses the same packs,
     * and the answer only decides what is drawn.
     */
    private static int ruleInt(LevelDef def, Identifier rule, int fallback) {
        var element = def.rules().get(rule);
        if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isNumber()) {
            return element.getAsInt();
        }
        var type = BuiltInRegistries.GAME_RULES.get(rule);
        return type == null ? fallback : (int) type.defaultValue();
    }

    /** Full-window orthographic projection in logical (scaled) GUI pixels. */
    public void beginGuiView() {
        spriteXScale = 1F;
        RenderSystem.viewport(0, 0, window.width(), window.height());
        RenderSystem.setProjectionMatrix(Matrix4f.ortho(0, guiWidth(), 0, guiHeight(), -10, 10));
        RenderSystem.setGuiShader();
    }

    /** GUI-space clipping rectangles; see {@link com.pvzce.client.gui.Clipping}. */
    public com.pvzce.client.gui.Clipping clipping() {
        return clipping;
    }

    /**
     * MC {@code Window.calculateScale}-shaped GUI scale. Auto uses the max
     * usable integer scale; a manual scale is clamped by the same constraints.
     */
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
        return MathUtil.ceilDiv(window.width(), guiScale());
    }

    public int guiHeight() {
        return MathUtil.ceilDiv(window.height(), guiScale());
    }

    /** Converts a raw framebuffer mouse X to logical GUI X. */
    public double guiMouseX(double mouseX) {
        return mouseX * guiWidth() / (double) Math.max(1, window.width());
    }

    /** Converts a raw framebuffer mouse Y (top-down) to logical bottom-up GUI Y. */
    public double guiMouseY(double mouseY) {
        return (window.height() - mouseY) * guiHeight() / (double) Math.max(1, window.height());
    }

    /**
     * The camera for the current window and level.
     *
     * <p>Built once per size rather than once per call. The render path asks for it around
     * fifteen times a frame, and each answer used to be two {@code Matrix4f}s and a handful of
     * records - allocation in the middle of the frame loop for a value that cannot change
     * while the window and the board stay the same size.
     *
     * <p>Invalidated by {@link #beginWorldView} when a camera with a different shape arrives,
     * which is what a resize produces, so the cache cannot outlive its inputs.
     */
    public PvzceCamera camera() {
        int width = window.width();
        int height = window.height();
        int columns = Math.max(1, level.width());
        int rows = Math.max(1, level.height());
        // Part of the key because the backdrop decides the stage: the pool's board is six
        // 85px lanes with the water drawn on its own frame, the front lawn's is five 100px
        // ones. A level swapped in at the same window size must not keep the old geometry.
        LevelStage.Geometry geometry = LevelStage.geometryFor(level.background());
        PvzceCamera cached = this.cachedCamera;
        if (cached == null || cachedWidth != width || cachedHeight != height
                || cachedColumns != columns || cachedRows != rows
                || !geometry.equals(cachedGeometry)) {
            cached = new PvzceCamera(width, height, columns, rows, geometry, 0F);
            this.cachedCamera = cached;
            this.cachedWidth = width;
            this.cachedHeight = height;
            this.cachedColumns = columns;
            this.cachedRows = rows;
            this.cachedGeometry = geometry;
        }
        return cached;
    }

    private PvzceCamera cachedCamera;
    private int cachedWidth = -1;
    private int cachedHeight = -1;
    private int cachedColumns = -1;
    private int cachedRows = -1;
    private LevelStage.Geometry cachedGeometry;

    /** Horizontal sprite correction so square world-space sprites match the board cell aspect. */
    public float spriteXScale() {
        return spriteXScale;
    }

    public void drawTexture(Identifier id, float x, float y, float w, float h, float z, float r, float g, float b, float a) {
        drawTextureRegion(id, 0F, 0F, 1F, 1F, x, y, w, h, z, r, g, b, a);
    }

    /** Draws a UV sub-region of a texture (u/v increase toward the world's +x/+y). */
    /**
     * Colour multiplier applied to every draw colour while it is on the stack, as
     * {@code {r, g, b, a}}.
     *
     * <p>One stack rather than a field: a part sheet is drawn by a loop that may itself
     * call back into a tinted draw, and a single field would leak the tint outwards.
     *
     * <p>The channels are a colour rather than one brightness because the two things that
     * use this stack need different answers: a resource drop wears its resource's own tint -
     * dimmed, and as warm as the art needs
     * ({@link com.pvzce.api.content.ResourceDef.DropTint}) - while a slowed zombie is drawn
     * <em>frozen</em>, and the frozen look is a blue tint, which one number cannot say. A
     * value above 1 is legal and is how the night lift brightens an entity the scene tint
     * has just darkened; GL clamps the finished pixel, not the vertex colour.
     */
    private final java.util.ArrayDeque<float[]> entityInk = new java.util.ArrayDeque<>();

    /** Multiplies the draw colour (per channel) until the matching {@link #popEntityTint}. */
    public void pushEntityTint(float r, float g, float b) {
        float[] current = entityInk();
        entityInk.push(new float[]{clampInk(current[0] * r), clampInk(current[1] * g),
                clampInk(current[2] * b), current[3]});
    }

    /** The grey form: brightens or dims every channel by the same factor. */
    public void pushEntityTint(float multiplier) {
        pushEntityTint(multiplier, multiplier, multiplier);
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
        entityInk.push(new float[]{current[0], current[1], current[2], MathUtil.clamp01(current[3] * multiplier)});
    }

    public void popEntityAlpha() {
        entityInk.poll();
    }

    private float[] entityInk() {
        float[] ink = entityInk.peek();
        return ink == null ? new float[]{1F, 1F, 1F, 1F} : ink;
    }

    /** The colour multiplier's own clamp: dimmable to nothing, brightenable a little. */
    private static float clampInk(float value) {
        return Math.max(0F, Math.min(MAX_ENTITY_INK, value));
    }

    /**
     * How far a channel may be multiplied up.
     *
     * <p>Four is a guard rail, not a look: the night lift lands near 1.5, and anything that
     * asked for more than this is a bug rather than a style.
     */
    private static final float MAX_ENTITY_INK = 4F;

    public void drawTextureRegion(Identifier id, float u0, float v0, float u1, float v1,
                                  float x, float y, float w, float h, float z, float r, float g, float b, float a) {
        float[] ink = entityInk();
        try {
            SpriteRenderer.textured(new Sprite(textures.getOrLoad(id), u0, v0, u1, v1), x, y, w, h, z,
                    r * ink[0], g * ink[1], b * ink[2], a * ink[3]);
        } catch (Exception e) {
            warnMissingTexture(id);
            drawMissingTexture(x, y, w, h, z, r * ink[0], g * ink[1], b * ink[2], a * ink[3]);
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
        float cg = g * ink[1];
        float cb = b * ink[2];
        float ca = a * ink[3];
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
        float shadowR = MathUtil.lerp(0.02F, 0.03F, nightBlend);
        float shadowG = MathUtil.lerp(0.03F, 0.06F, nightBlend);
        float shadowB = MathUtil.lerp(0.08F, 0.22F, nightBlend);
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

    /**
     * Pushes the tint one board entity wears, until the matching {@link #popEntityTint}.
     *
     * <p>One frame for every effect rather than one per effect: the night lift and the
     * frozen look multiply together here, so a caller pushes once and pops once whatever
     * applies to the entity it is drawing. Both are presentation and both are about the same
     * question - "how is this one thing lit" - which is why they share a place.
     */
    public void pushEntityLook(com.pvzce.client.ClientEntity entity) {
        float lift = com.pvzce.client.renderer.EntityVisuals.liftsAtNight(entity.kind())
                ? com.pvzce.client.renderer.EntityVisuals.NIGHT_LIFT
                : 0F;
        float r = liftChannel(lift, worldTintR);
        float g = liftChannel(lift, worldTintG);
        float b = liftChannel(lift, worldTintB);
        if (entity.chilled()) {
            r *= com.pvzce.client.renderer.EntityVisuals.CHILLED_TINT_R;
            g *= com.pvzce.client.renderer.EntityVisuals.CHILLED_TINT_G;
            b *= com.pvzce.client.renderer.EntityVisuals.CHILLED_TINT_B;
        }
        if (entity.charmed()) {
            // A charmed zombie *and* a frozen one multiplies both, which reads as "cold and on
            // my side" - the two states are independent and both are true, so the colour is the
            // product rather than one of them winning.
            r *= com.pvzce.client.renderer.EntityVisuals.CHARMED_TINT_R;
            g *= com.pvzce.client.renderer.EntityVisuals.CHARMED_TINT_G;
            b *= com.pvzce.client.renderer.EntityVisuals.CHARMED_TINT_B;
        }
        float[] current = entityInk();
        entityInk.push(new float[]{clampInk(current[0] * r), clampInk(current[1] * g),
                clampInk(current[2] * b), current[3]});
    }

    /**
     * The per-channel ink that undoes {@code amount} of one channel of the scene tint.
     *
     * <p>The shader draws {@code texel * ink * tint}, so undoing part of the tint means
     * dividing by it here: {@code 1 + amount * (1/tint - 1)}. Zero leaves the channel alone
     * (the entity wears the full tint), one cancels it outright (the entity looks like it
     * does at noon), and the fraction in between is what {@code NIGHT_LIFT} asks for.
     *
     * <p>Clipped below at a fifth: a tint channel of zero would divide by zero, and no tint
     * this renderer produces comes close.
     */
    private static float liftChannel(float amount, float tint) {
        if (amount <= 0F) {
            return 1F;
        }
        float inverse = 1F / Math.max(0.2F, tint);
        return 1F + amount * (inverse - 1F);
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
                LOGGER.warn("missing texture {}", id, new Throwable("missing " + id));
            }
        }
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
        if (screen == null || screen.blurredBackdrop()) {
            // Captured here, not at draw time: the back buffer still holds the frame the player is
            // looking at right now, and the incoming screen is about to paint over it.
            blurredBackdrop.capture(window.width(), window.height());
        }
        screens.push(screen);
    }

    /** The blurred frame a screen was opened over; see {@link com.pvzce.client.renderer.BlurredBackdrop}. */
    public com.pvzce.client.renderer.BlurredBackdrop backdrop() {
        return blurredBackdrop;
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

    /**
     * The player picker, which is the title screen's own left column.
     *
     * <p>Kept as a named entry point because "back to the menu" is a thing several paths mean
     * (leaving a level, a protocol mismatch, the level list's declared root) and they should
     * not each have to know that the player list is drawn on the title screen.
     */
    public void showWorldSelect() {
        showTitle();
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
        roundClearOpen = false;
        if (music != null) {
            music.startLevel("pvzce:music/grasswalk");
        }
        // Only a run that skipped the seed chooser still owes the player its opening
        // conversation; one that went through the chooser has already shown it.
        String levelId = level.levelId();
        com.pvzce.api.content.LevelDialogue opening = levelId != null && levelId.equals(directDialogueLevelId)
                ? levelDialogue(levelId) : com.pvzce.api.content.LevelDialogue.EMPTY;
        directDialogueLevelId = null;
        setScreenReplacing(new InGameScreen(this, opening));
        if (deferredRoundClear != null) {
            com.pvzce.common.network.packet.RoundClearS2C pending = deferredRoundClear;
            deferredRoundClear = null;
            showRoundClear(pending);
        }
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
    void requestFreshRunDirectly(String levelId, boolean restart) {
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
     *
     * <p>The whole block, not just its lines: how the conversation is staged (the opening and
     * closing slides) is part of what the overlay is handed, and splitting it into a second
     * lookup would let the two answers come from different reads.
     *
     * <p>Empty when the player switched 剧情 off: both places that play a conversation (the
     * seed chooser and the in-game overlay of a level entered without one) build it from this,
     * so "no dialogue" is one decision rather than a check each of them has to make.
     */
    public com.pvzce.api.content.LevelDialogue levelDialogue(String levelId) {
        if (!config.storyEnabled()) {
            return com.pvzce.api.content.LevelDialogue.EMPTY;
        }
        Identifier id = Identifier.tryParse(levelId);
        LevelDef def = id == null ? null : BuiltInRegistries.LEVELS.get(id);
        return def == null ? com.pvzce.api.content.LevelDialogue.EMPTY : def.dialogue();
    }

    /** Whether entering a level plays its opening conversation (the 剧情 switch). */
    public boolean storyEnabled() {
        return config.storyEnabled();
    }

    /** The 剧情 switch; persisted, and read on the next level entry rather than mid-scene. */
    public void setStoryEnabled(boolean enabled) {
        config.setStoryEnabled(enabled);
        config.save();
    }

    /**
     * Asks the server to buy a locked level outright.
     *
     * <p>The price is deliberately not sent: the server reads it from the level's own
     * definition, so a modified client can choose which level to buy but not what it
     * costs. The refreshed level list comes back on its own.
     */
    /**
     * Asks the server to buy one shop item.
     *
     * <p>The world name travels with it because the shop is reached from the title screen: the
     * server has no current world then, and would otherwise charge whichever one it last
     * defaulted to. Nothing about the price is sent - the server re-reads it.
     */
    public void buyShopItem(String itemId) {
        connection.send(new com.pvzce.common.network.packet.BuyShopItemC2S(itemId, currentWorld));
    }

    public void buyLevelUnlock(String levelId) {
        connection.send(new UnlockLevelC2S(levelId, currentWorld));
    }

    /**
     * Starts a level with the cards the player picked.
     *
     * <p>The pick is sent and then forgotten: the next time this level's chooser opens it opens
     * empty, with only the level's own fixed cards pinned. Remembering the last bar per level
     * meant a player who came back a week later had to clear a deck they no longer wanted before
     * they could build the one they did - and the bar they picked for a level is not a property
     * of the level.
     */
    public void startLevelWithSeeds(String levelId, boolean restart, List<String> selectedSeeds) {
        startLevelWithSeedsAndBuffs(levelId, restart, selectedSeeds, List.of());
    }

    /**
     * As above, plus the level buffs the chooser had switched on.
     *
     * <p>Both halves travel in one packet because they are one decision made on one screen, and
     * because the buff list doubles as what the world remembers: whatever a run starts with is
     * what the next run pre-selects. No separate "save my preferences" step exists, so there is
     * no way for the stored list and the last run to disagree.
     */
    public void startLevelWithSeedsAndBuffs(String levelId, boolean restart,
                                            List<String> selectedSeeds, List<String> selectedBuffs) {
        connection.send(new PlayLevelC2S(levelId, currentWorld, restart,
                List.copyOf(selectedSeeds), List.copyOf(selectedBuffs)));
    }

    /**
     * Opens the "Choose Your Seeds" screen before entering a level from the world map.
     *
     * <p>Only for levels that have no run to resume; see {@link #enterLevelFromMenu}. The
     * chooser is nested under whatever asked for it (the level setup screen), so its back
     * target pops.
     */
    public void openSeedSelection(LevelListS2C.LevelInfo info, boolean restart) {
        openSeedSelection(info, List.of(), null);
    }

    /**
     * Whether 关卡准备 has anything to ask for this level.
     *
     * <p>The level declares who may play it ({@code playable_teams}), and the server sends the
     * verdict per team. One playable team is not a choice, so the screen is skipped: the entry
     * flow goes straight to whatever comes next, exactly as if the player had clicked the only
     * panel there was. A level that names none keeps the screen - it is a data error, and a
     * visible menu is a better failure than silently starting as nobody.
     */
    public static boolean offersTeamChoice(LevelListS2C.LevelInfo info) {
        return info.teams().stream().filter(LevelListS2C.TeamInfo::playable).count() != 1;
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
     * submitted.
     *
     * <p>Everything else goes through as many pre-game screens as the <em>level</em> says it
     * needs: 关卡准备 only when it offers more than one playable side (see
     * {@link #offersTeamChoice}), and then the seed chooser - which is a pass-through for a
     * level with nothing to choose, whether its deck is fixed or it deals its own cards.
     * This is the only place that ordering is written; the level list, the smoke hook and the
     * editor's 测试 all arrive here.
     */
    public void enterLevelFromMenu(LevelListS2C.LevelInfo info) {
        if (info.hasRunningSave()) {
            requestLevel(info.id(), false);
        } else if (offersTeamChoice(info)) {
            // The level names more than one side, so which one to play is a real question and
            // this is the screen that asks it. The screen forwards back here when answered.
            openScreen(new com.pvzce.client.gui.screens.LevelSetupScreen(this, info));
        } else if (skipsSeedScreen(info)) {
            // Nothing to choose and nothing to preview: straight in, with the bar the server
            // resolves for a run nobody chose cards for (see SeedSelection.defaultFor).
            startLevelWithSeedsAndBuffs(info.id(), false, List.of(), List.of());
        } else {
            // Including the levels that deal their own cards: the chooser is a pass-through
            // for those (nothing to pick, nothing drawn), and it is where a level's lawn,
            // opening conversation and zombie line-up are shown before it starts.
            openSeedSelection(info, false);
        }
    }

    /**
     * True when this level says its card screen is not a question it has.
     *
     * <p>The level's own flag ({@code "seed_screen": false}) and nothing else: a fixed deck on
     * its own is not enough, because 1-1 and 2-5 also fix their cards and still want the screen -
     * one for its opening conversation, the other because the player picks the rest of the bar.
     *
     * <p>The buff page has a veto. A level that offers buffs is asking a real question, and the
     * page it is asked on is the screen this would skip; a level that wants both writes
     * {@code seed_screen: true} or offers no buffs.
     */
    public boolean skipsSeedScreen(LevelListS2C.LevelInfo info) {
        return info != null && !seedScreenFor(info.id()) && !offersBuffChoice(info);
    }

    /** The level's own answer to "is the card screen part of entering me", default true. */
    private boolean seedScreenFor(String levelId) {
        Identifier id = Identifier.tryParse(levelId);
        LevelDef def = id == null ? null : BuiltInRegistries.LEVELS.get(id);
        return def == null || def.seedScreen();
    }

    /** True when the level's payload has a buff page with something on it to pick. */
    private static boolean offersBuffChoice(LevelListS2C.LevelInfo info) {
        return info.payload().maxBuffSlots() > 0 && !info.payload().buffPool().isEmpty();
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
        openScreen(createSeedSelection(info, initialSelection, null, onBack));
    }

    /** As above, with the buffs a resumed run was saved with (see the save prompt's restart). */
    public void openSeedSelection(LevelListS2C.LevelInfo info,
                                  List<String> initialSelection, List<String> initialBuffs,
                                  Runnable onBack) {
        openScreen(createSeedSelection(info, initialSelection, initialBuffs, onBack));
    }

    /**
     * Shows the between-rounds summary, and opens the card chooser once it is confirmed.
     *
     * <p>Deferred while the client is not on the board, exactly like the save prompt: the packet
     * can land while a menu is open, and a dialog stacked on the title screen would be asking
     * about a run the player is not looking at.
     */
    public void showRoundClear(com.pvzce.common.network.packet.RoundClearS2C summary) {
        if (!(currentScreen() instanceof InGameScreen)) {
            deferredRoundClear = summary;
            return;
        }
        if (roundClearOpen) {
            return;
        }
        RoundClearDialog dialog = RoundClearDialog.create(this, summary,
                () -> openRoundSeedSelection(level.levelId(), summary.round() + 1,
                        currentBarIds(), currentBuffIds()));
        roundClearOpen = true;
        openRoundClear = dialog;
        dialog.onClose(() -> {
            roundClearOpen = false;
            openRoundClear = null;
        });
        currentScreen().showDialog(dialog);
    }

    /** The cards the run is holding right now, as the round chooser's starting selection. */
    private List<String> currentBarIds() {
        List<String> ids = new ArrayList<>();
        for (com.pvzce.common.network.packet.SlotInfo slot : level.slots()) {
            if (slot.defId() != null && !slot.defId().isBlank()) {
                ids.add(slot.defId());
            }
        }
        return ids;
    }

    /** The run's own buffs, so the round chooser's buff page opens where the run left it. */
    private List<String> currentBuffIds() {
        return level.activeBuffs();
    }

    /**
     * Closes a round-clear dialog whose round the run has already moved past.
     *
     * <p>The server gives up waiting for an answer eventually (see its
     * {@code round_clear_timeout_ticks}) and starts the next round with the bar the player
     * already had. A client still showing "round 1 is over" over a board that is playing round 2
     * is a modal nobody can dismiss and a round nobody was told about, so the round sync closes
     * it: the run is the authority on which round it is in.
     *
     * @param startedRound the round the server has just begun
     */
    public void onRoundStarted(int startedRound) {
        if (openRoundClear == null || startedRound <= openRoundClear.round()) {
            return;
        }
        openRoundClear.close();
    }

    /**
     * Opens the card chooser for the next round of an endless run.
     *
     * <p>The same page as entering a level, and deliberately so - what changes is what its
     * confirm button sends. There is nothing to back out to: the server has stopped simulating
     * and is waiting for this answer, so the chooser is opened without a back target and the
     * run continues with the player's old bar if they never answer (see the server's timeout).
     *
     * @param levelId    the level the run is in
     * @param round      the round the choice is for, for the title
     * @param current    the bar the run is playing with, which the chooser starts from
     * @param buffs      the run's own buff list, so the buff page opens where it was left
     */
    public void openRoundSeedSelection(String levelId, int round, List<String> current,
                                       List<String> buffs) {
        LevelListS2C.LevelInfo info = findLevelInfo(levelId);
        if (info == null) {
            // No registry snapshot yet. A player who entered this level straight from a smoke
            // hook - or whose list was refreshed while they were on the board - has none, and the
            // page cannot be built without one, so ask for the list and open the chooser when it
            // arrives. The run is waiting on the server's own timeout the whole time.
            pendingRoundChooser = new PendingRoundChooser(levelId, round, List.copyOf(current),
                    List.copyOf(buffs));
            connection.send(new com.pvzce.common.network.packet.RequestLevelListC2S(currentWorld));
            return;
        }
        openScreen(createSeedSelection(info, current, buffs, null, true, round));
    }

    /**
     * Opens a round chooser that was waiting for the level list.
     *
     * <p>Called from {@link #setLevelList}: the list is what the page is built from, and a
     * request that came back with nothing still leaves the run on the server's own timeout.
     *
     * @return true when a waiting chooser was opened
     */
    private boolean openPendingRoundChooser() {
        PendingRoundChooser pending = pendingRoundChooser;
        if (pending == null) {
            return false;
        }
        pendingRoundChooser = null;
        LevelListS2C.LevelInfo info = findLevelInfo(pending.levelId());
        if (info == null) {
            LOGGER.warn("No level list entry for {}; the next round keeps the current cards",
                    pending.levelId());
            return false;
        }
        openScreen(createSeedSelection(info, pending.cards(), pending.buffs(), null, true,
                pending.round()));
        return true;
    }

    /**
     * A round chooser waiting for the level list it is built from.
     *
     * @param levelId the level the run is in
     * @param round   the round the choice is for
     * @param cards   the bar the chooser starts from
     * @param buffs   the run's buffs, so the buff page opens where it was left
     */
    private record PendingRoundChooser(String levelId, int round, List<String> cards,
                                       List<String> buffs) {
    }

    /** Sends the next round's card selection to the running level. */
    public void reselectCards(String levelId, List<String> selectedSeeds) {
        connection.send(new com.pvzce.common.network.packet.ReselectCardsC2S(levelId, currentWorld,
                List.copyOf(selectedSeeds)));
    }

    /**
     * The buffs the chooser should start with when nobody has chosen anything yet: a resumed
     * run's own list when there is one, otherwise the world's auto list.
     *
     * <p>The order matters and mirrors the server: a save that already answered the question is
     * not asked again. The auto list is filtered down to what this level actually offers when the
     * screen is built, so a stale id neither shows up nor takes a slot.
     */
    private List<String> autoBuffSelectionFor(String levelId, List<String> savedBuffs) {
        if (savedBuffs != null) {
            return savedBuffs;
        }
        // The world's list, in the order the server has it. Capped rather than trimmed here:
        // the screen knows the resolved count and drops whatever does not fit.
        return profile.autoBuffIds();
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
     * The buffs this level hands out whether or not the player asked for them.
     *
     * <p>Read from the local level definition, exactly like {@link #lockedSlotsFor} and for the
     * same reason: the client loads the same data packs, and the answer only decides what the
     * screen draws. The server resolves its own copy again when the level starts, so a client
     * that lied about this could not switch a locked buff off.
     */
    public List<String> lockedBuffsFor(String levelId) {
        Identifier id = Identifier.tryParse(levelId);
        LevelDef def = id == null ? null : BuiltInRegistries.LEVELS.get(id);
        if (def == null) {
            return List.of();
        }
        return def.buffPlan().fixedBuffs().stream().map(Identifier::toString).toList();
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
                                                  List<String> initialSelection, List<String> initialBuffs,
                                                  Runnable onBack) {
        return createSeedSelection(info, initialSelection, initialBuffs, onBack, false, 0);
    }

    /**
     * @param nextRound       true to pick the next endless round's cards rather than start a run
     * @param nextRoundNumber the round that choice is for, one-based
     */
    private ChooseSeedsScreen createSeedSelection(LevelListS2C.LevelInfo info,
                                                  List<String> initialSelection, List<String> initialBuffs,
                                                  Runnable onBack, boolean nextRound,
                                                  int nextRoundNumber) {
        return new ChooseSeedsScreen(this, info.id(), info.name(), info.seedPool(),
                info.maxSeedSlots(), info.previewZombies(), info.width(), info.height(),
                info.sceneCells(), initialSelection, onBack != null, onBack, lockedSlotsFor(info.id()),
                info.payload().backgroundId(), info.payload().hiddenSceneElements(),
                // A conveyor level: nothing to choose, but still worth a look at the lawn, the
                // conversation and the zombies - see ChooseSeedsScreen's pass-through.
                dealsItsOwnCards(info.id()),
                info.payload().buffPool(), info.payload().maxBuffSlots(),
                lockedBuffsFor(info.id()), autoBuffSelectionFor(info.id(), initialBuffs),
                nextRound, nextRoundNumber);
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
     * The save prompt's "重新开始" option: keep the loaded save untouched and
     * paused while the client shows seed selection. Submitting the new cards
     * sends {@code PlayLevelC2S(restart=true)}, which deletes the old save and
     * creates the fresh level with the chosen bar. Backing out re-opens the
     * prompt so the player can still continue.
     */
    public void openSeedSelectionForRestart(LevelSavePromptS2C prompt) {
        // The answer is about the saved run, not about the next one: the chooser below can be
        // backed out of, and a player who does so has still refused the run they were shown. Sent
        // before the chooser opens so the decision sticks either way - otherwise the save stayed on
        // disk, and picking the level again offered the same night they had just turned down.
        connection.send(new com.pvzce.common.network.packet.DiscardLevelSaveC2S(
                prompt.levelId(), prompt.worldName()));
        LevelListS2C.LevelInfo info = findLevelInfo(prompt.levelId());
        if (info == null) {
            // No registry snapshot (for example a direct smoke request): fall back to the
            // server-side restart, which is the same thing minus the page. Levels whose cards
            // are not the player's to pick (a fixed deck, a conveyor belt) go through the
            // chooser as a pass-through instead - that page is also the level's preview.
            connection.send(new RestartLevelC2S(prompt.levelId(), prompt.worldName(), List.of()));
            return;
        }
        if (skipsSeedScreen(info)) {
            // The same level that would not show the screen on the way in does not show it on
            // the way back in either: restarting it is not a card choice.
            connection.send(new RestartLevelC2S(prompt.levelId(), prompt.worldName(), List.of()));
            return;
        }
        // The chooser opens empty either way: the bar a run happens to be holding is not a
        // choice the player made for the *next* run, and the level's own fixed cards are pinned
        // by the chooser itself (see `lockedSlotsFor`).
        List<String> initial = List.of();
        // Unlike the card bar, the buff list IS carried over: a run's buffs are the world's
        // standing preference, and the same file is where the last answer was written. The save
        // is read from disk rather than asked for, exactly like the level's dialogue is - the
        // server is deliberately not told to leave, and it has not sent this list anywhere.
        // ``null`` (an old save with no buff record) falls through to the world's auto list.
        List<String> initialBuffsFinal = savedBuffs(prompt.levelId(), prompt.worldName());
        // The old run is still loaded (and frozen) behind the chooser, so its music
        // would otherwise keep playing under the chooser theme until the seed
        // selection finally replaced the level. Only the client stops here: the
        // server is deliberately not told to leave, because backing out returns to
        // the save prompt over the same instance.
        silenceLevelMusic();
        openSeedSelection(info, initial, initialBuffsFinal, () -> {
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

    /**
     * The buffs a run on disk was started with, or {@code null} when that save has no record.
     *
     * <p>Read through the same file format the server writes, which is why the reader for it
     * lives in {@code LevelBuffSelection}: one file, one shape, and this is only a client that
     * happens to have the file on its own disk (single player is one process with two halves).
     */
    private List<String> savedBuffs(String levelId, String worldName) {
        Identifier id = Identifier.tryParse(levelId);
        if (id == null) {
            return null;
        }
        Path worldDir = com.pvzce.common.util.WorldPaths.worldDir(
                gameDir, com.pvzce.common.util.WorldPaths.sanitize(worldName));
        Path saveFile = com.pvzce.common.util.LevelKey.levelDir(worldDir, id).resolve("level.dat");
        if (!java.nio.file.Files.isRegularFile(saveFile)) {
            return null;
        }
        try {
            java.util.List<Identifier> ids =
                    com.pvzce.server.LevelBuffSelection.readSavedTag(
                            com.pvzce.common.nbt.NbtIo.readCompressed(saveFile));
            return ids == null ? null : ids.stream().map(Identifier::toString).toList();
        } catch (Throwable t) {
            // An unreadable save is the server's problem to report; here it just means "no
            // remembered buffs", which falls back to the world's auto list.
            return null;
        }
    }

    /** Stops the level tracks locally, without telling the server to close the level. */
    private void silenceLevelMusic() {
        if (music != null) {
            music.leaveLevel();
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
     * and the way back in is the level's own way in: the chooser for a level that has
     * one, and straight into the run for a level that says its card screen is not a
     * question ({@link #skipsSeedScreen}) - the same decision {@link #enterLevelFromMenu}
     * makes, because "restart" is that entry minus the old run.
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
        if (skipsSeedScreen(info)) {
            // The same level that goes straight in on the way in goes straight back in on a
            // restart. This used to be the one entrance that did not ask: entering from the list
            // and the save prompt's restart both check `skipsSeedScreen`, and the pause menu sent
            // every level through the chooser - so restarting 4-5 (whose bar is two fixed cards)
            // offered a card screen the level had said it does not have. (The user found it:
            // "如果选择重新开始，那么还会有选卡页面，是不是因为走了不同的路？")
            requestFreshRunDirectly(levelId, true);
            return;
        }
        connection.send(new LeaveLevelC2S());
        clearLevelClientState();
        List<String> initial = List.of();
        // Installed as the root (the level was just closed, so nothing is underneath), which
        // is also what makes the chooser a restart: onBack non-null is the one thing that
        // says "there is a state to come back to". A conveyor level runs through it too, as a
        // pass-through - that is where its zombie preview and opening conversation live.
        // No buff list to carry over either: this is a fresh run of a level the player has just
        // left, so the world's auto list is the answer, and the chooser applies it itself.
        setScreenReplacing(createSeedSelection(info, initial, null, this::showLevelList));
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

    /**
     * The three font roles, plus the atlases behind them. Text is drawn through a
     * role ({@code fonts().body()}), never through a whole-renderer call: the role is
     * what says "this string is a button", and the renderer decides which typeface
     * and pixel size that means.
     */
    public Fonts fonts() {
        return fonts;
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

    /**
     * Whether the shader effects run right now: the player's setting, unless the level vetoes it.
     *
     * <p>One gate for both halves, because "are the shaders on" decides the day/night tint, the
     * entity lights and the water pass together - a level that turns them off has to turn all
     * three off at once. The level can only ever say <em>off</em>: a player who has shaders
     * disabled does not get them back by entering a level, and a level that says nothing about
     * it follows whatever the player chose.
     */
    public boolean shadersEnabled() {
        return config.shadersEnabled() && !level.shadersDisabled();
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
        String world = com.pvzce.common.util.WorldPaths.sanitize(currentWorld);
        // A level list describes exactly one world - its 进行中/已通关 labels are the save
        // files of that world. Carrying the previous world's snapshot into the new one showed
        // those labels for the wrong world (and they drive whether entering a level loads a
        // save or opens the seed chooser), so drop it and let the level list refetch.
        if (!java.util.Objects.equals(this.currentWorld, world)) {
            levelList = List.of();
            // The profile is per world too: keeping the previous world's coins on
            // screen while the new world's list loads would show another world's money.
            profile.reset();
        }
        this.currentWorld = world;
        // Written through on every switch, so the next start opens on the player that was
        // actually being played as; an unchanged switch writes nothing.
        if (!world.equals(config.lastWorld())) {
            config.setLastWorld(world);
            config.save();
        }
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

    /** As above, with the buff half of the backpack: its slot count and the world's auto list. */
    public void setProfile(int coins, List<String> unlocked, boolean unlockAll, int seedSlots,
                           int buffSlots, List<String> autoBuffs) {
        profile.apply(coins, unlocked, unlockAll, seedSlots, buffSlots, autoBuffs);
    }

    /** As above, with the buffs this world has been given - what the shop and the padlocks read. */
    public void setProfile(int coins, List<String> unlocked, boolean unlockAll, int seedSlots,
                           int buffSlots, List<String> autoBuffs, List<String> unlockedBuffs) {
        profile.apply(coins, unlocked, unlockAll, seedSlots, buffSlots, autoBuffs, unlockedBuffs);
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
        // A round chooser that could not be built when it was asked for now can be: it is the
        // level list that carries the card pool, the board and the level's name.
        openPendingRoundChooser();
        smoke.openSeedChooserForSmoke(this.levelList);
        // A test run saved the level and asked the server for a reload; the list that
        // arrives next carries the saved definition, so this is where its seed chooser can
        // open with current data.
        if (pendingTestLevelId != null) {
            String wanted = pendingTestLevelId;
            pendingTestLevelId = null;
            for (LevelListS2C.LevelInfo info : this.levelList) {
                if (wanted.equals(info.id())) {
                    // Belt levels come through here too: the chooser shows their preview and
                    // starts itself, which is what "test this level" means for them as well.
                    openSeedSelection(info, true);
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
}
