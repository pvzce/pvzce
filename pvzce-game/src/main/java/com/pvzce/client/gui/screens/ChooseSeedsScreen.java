package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.SceneVisibility;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.SeedCardRenderer;
import com.pvzce.client.gui.components.AbstractWidget;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.DialogueOverlay;
import com.pvzce.client.gui.components.NinePatch;
import com.pvzce.client.renderer.LevelStage;
import com.pvzce.client.renderer.SceneTileRenderer;
import com.pvzce.common.network.packet.SceneSyncS2C;
import com.pvzce.common.network.packet.SeedOption;
import com.pvzce.common.util.MathUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PVZ-style "Choose Your Seeds" screen.
 *
 * <p>Entering the level first pans the original house/lawn/road backdrop to
 * the right, reveals the level's distinct zombie types on the road, then the
 * wooden seed chooser slides in from the left.</p>
 *
 * <p>The panel is content-sized horizontally rather than screen-sized: the
 * pool keeps a fixed card size (derived from the GUI size), cards wrap to as
 * many columns as the panel's width budget allows, and the wooden frame
 * shrinks back to the widest row. Vertically the board covers the whole left
 * column under the chosen-seed row, so no backdrop shows through beneath it;
 * inside, the pool is compact and top-aligned and the lower part of the board
 * is left as plain wood. The title and the counter sit on the frame's top bar.
 * The pool is split into resource / tool / plant blocks (in that order, no
 * headers), each block left-aligned and free to wrap onto several rows. The
 * chosen-seed row is drawn above the panel's top edge, mirroring the in-level
 * card bar's resource | plant | tool order, so it never overlaps the wooden
 * frame. Pools taller than the panel scroll inside it with the mouse wheel.</p>
 */
public final class ChooseSeedsScreen extends Screen {
    private static final Identifier PANEL_BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/screen/seeds/seed_chooser_background");
    private static final Identifier CARD_BACKGROUND = com.pvzce.client.gui.SeedCardRenderer.CARD_BACKGROUND;
    private static final Identifier SUN_BANK =
            Identifier.withDefaultNamespace("textures/gui/hud/sun_bank");
    private static final String SUN_CARD_ID = com.pvzce.common.PvzceIds.SUN.toString();
    /** The card kinds, spelled by the one enum that also drives the shared painter. */
    private static final String KIND_RESOURCE = kindName(SeedCardRenderer.CardKind.RESOURCE);
    private static final String KIND_PLANT = kindName(SeedCardRenderer.CardKind.PLANT);
    private static final String KIND_TOOL = kindName(SeedCardRenderer.CardKind.TOOL);
    private static final String KIND_BUFF = com.pvzce.common.core.SeedOptions.BUFF_KIND;

    private static String kindName(SeedCardRenderer.CardKind kind) {
        return kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static final String SOUND_SEEDLIFT = "pvzce:sfx/ui/seedlift";
    private static final String SOUND_TAP = "pvzce:sfx/ui/tap";

    private static final float PANEL_NATIVE_WIDTH = 465F;
    private static final float PANEL_NATIVE_HEIGHT = 513F;
    private static final float PANEL_BORDER = 20F;
    /** Texture rows that make up the frame's visible top bar (border slice + tiled wood). */
    private static final float PANEL_BAR_NATIVE = 31F;
    /** Texture x where the gold ornament at the bar's left end stops. */
    private static final float PANEL_ORNAMENT_RIGHT = 62F;

    /** Seed packets are 100x140 artwork; the chooser keeps that portrait ratio. */
    private static final float CARD_ASPECT = 0.74F;
    private static final float CARD_HEIGHT_RATIO = 0.122F;
    private static final float CARD_MIN_HEIGHT = 44F;
    private static final float CARD_MAX_HEIGHT = 96F;

    /** Original PvZ dims a picked pool packet; unchosen packets stay bright. */
    private static final float CHOSEN_BRIGHTNESS = 0.68F;

    private static final float PANEL_MAX_WIDTH_RATIO = 0.70F;
    private static final float PANEL_MIN_WIDTH_RATIO = 0.40F;

    private static final long PAN_NANOS = 1_200_000_000L;
    private static final long PANEL_DELAY_NANOS = 1_250_000_000L;
    private static final long PANEL_SLIDE_NANOS = 450_000_000L;
    private static final long PREVIEW_DELAY_NANOS = 350_000_000L;
    private static final long PREVIEW_FADE_NANOS = 850_000_000L;
    private static final long FLY_NANOS = 360_000_000L;
    /** Panel-slides-out + camera-pans-home beat before the level is actually started. */
    private static final long EXIT_NANOS = 520_000_000L;
    /**
     * The same beat when there is no panel to slide out.
     *
     * <p>With nothing but the camera moving, half a second is a snap. The way back takes
     * as long as the way out did, which is what makes it read as one movement.
     */
    private static final long PREVIEW_EXIT_NANOS = PAN_NANOS;
    /**
     * How long a level with nothing to choose shows its zombies before starting.
     *
     * <p>Measured from the pan, not from the screen opening, so a dialogue cannot eat into
     * the look at the lane. It is the pan out plus the preview's own fade plus a beat to
     * see it: there is no panel to read in this mode, so waiting for one would be three
     * seconds of an empty road.
     */
    private static final long AUTO_START_NANOS = 2_100_000_000L;

    private final String levelId;
    /** The backdrop this level will be played on, or {@code null} for the built-in yard. */
    private final Identifier background;
    /** Scene elements the level does not draw; the preview has to hide the same ones. */
    private final SceneVisibility sceneVisibility;
    private final String levelName;
    private final List<SeedOption> options;
    private final int maxSeedSlots;
    private final List<String> previewZombies;
    private final int levelWidth;
    private final int levelHeight;
    private final String[][] scene;
    private final boolean restart;
    private final Runnable onBack;
    private final List<String> selectedOrder = new ArrayList<>();
    /** The level's own cards; always in the bar and never removable. */
    private final Set<String> lockedSlots = new LinkedHashSet<>();
    private final List<Flight> flights = new ArrayList<>();
    private final List<ClientEntity> previewEntities = new ArrayList<>();
    private boolean previewAnimationsReady;

    private long startNanos;
    /**
     * When the camera pan and the panel's slide-in begin.
     *
     * <p>Zero while the level's opening dialogue is still on screen: the pan is the
     * "normal flow" the dialogue hands over to, so it must not have run behind it. With
     * no dialogue this is {@link #startNanos}, which is what it always was.
     */
    private long panStartNanos;
    /** The level's opening conversation, or {@code null} when it has none. */
    private DialogueOverlay dialogue;
    /** Non-zero once the start has been requested; the packet waits for the animation. */
    private long exitNanos;
    /** True once the start packet went out, so the auto-start cannot fire twice. */
    private boolean startSent;
    /**
     * True when the level leaves the player nothing to choose.
     *
     * <p>Then this screen is only the level's opening choreography: the camera pans down
     * the lane, the level's zombies walk in, and it pans home again. No wooden frame, no
     * card pool, no buttons - a chooser with one possible answer is a page the player has
     * to wait for rather than read. Worked out once, from the same data the panel would
     * have been built from.
     */
    private final boolean previewOnly;
    /**
     * The level buffs the player may switch on, and the level's own buffs.
     *
     * <p>Separate from {@link #options} because a buff is not a card and the two pages are never
     * on screen together - but they are drawn by the same painter and laid out by the same code,
     * which is why this is a second option list rather than a second screen.
     */
    private final List<SeedOption> buffOptions;
    private final int maxBuffSlots;
    private final Set<String> lockedBuffs = new LinkedHashSet<>();
    /**
     * Buffs the player may see but not switch on yet.
     *
     * <p>Read off the pool the server sent (see {@code SeedOptions.LOCKED_OPTION}) rather than
     * recomputed from the local profile: the pool is the server's answer to "what may this player
     * have", and a second answer here could only disagree with it. A padlocked buff is worth
     * showing - it is the same "there is something here you have not earned" a locked card says -
     * and {@code toggleBuff} refuses it.
     */
    private final Set<String> lockedBuffOptions = new LinkedHashSet<>();
    private final List<String> selectedBuffs = new ArrayList<>();
    /** 0 = the card page, 1 = the buff page. A level with no buffs never leaves 0. */
    private int page;
    /** The tab buttons' rectangles, filled in by {@link #updateLayout()} and read by clicks. */
    private final float[][] tabRects = {new float[4], new float[4]};
    private boolean tabsVisible;
    private float panelX;
    private float panelY;
    private float panelW;
    private float panelH;
    private float panelPad;
    private float gridX;
    private float gridViewTop;
    private float gridViewBottom;
    private float gridContentH;
    private float gridScroll;
    private float gridMaxScroll;
    private float cardW;
    private float cardH;
    private float cardGap;
    private float sectionGap;
    private int columns;
    private List<CardSection> sections = List.of();
    private float topBarX;
    private float topBarY;
    private float topCardW;
    private float topCardH;
    private float topGap;
    private int topSlots;
    private float titleY;
    private float titleScale;
    private float titleInset;
    private float startButtonX;
    private float startButtonY;
    private float startButtonW;
    private float startButtonH;
    private float clearButtonX;
    private float clearButtonY;
    private float clearButtonW;
    private float clearButtonH;
    private Button startButton;
    private Button clearButton;
    private Button backButton;

    /** One kind block ("resource" / "plant" / "tool") of the left-hand pool. */
    private static final class CardSection {
        final String kind;
        final List<Integer> indices = new ArrayList<>();
        int rows = 1;
        float height;
        /** Bottom-up GUI y of the block's top edge. */
        float top;

        CardSection(String kind) {
            this.kind = kind;
        }
    }

    private static final class Flight {
        final SeedOption option;
        final float startX;
        final float startY;
        final long startNanos;

        Flight(SeedOption option, float startX, float startY) {
            this.option = option;
            this.startX = startX;
            this.startY = startY;
            this.startNanos = System.nanoTime();
        }
    }

    public ChooseSeedsScreen(PvzceClient client, String levelId, String levelName,
                             List<SeedOption> options, int maxSeedSlots,
                             List<String> previewZombies, int levelWidth, int levelHeight,
                             List<SceneSyncS2C.Cell> sceneCells, List<String> initialSelection,
                             boolean restart) {
        this(client, levelId, levelName, options, maxSeedSlots, previewZombies,
                levelWidth, levelHeight, sceneCells, initialSelection, restart, null, List.of(),
                null, List.of(), false);
    }

    public ChooseSeedsScreen(PvzceClient client, String levelId, String levelName,
                             List<SeedOption> options, int maxSeedSlots,
                             List<String> previewZombies, int levelWidth, int levelHeight,
                             List<SceneSyncS2C.Cell> sceneCells, List<String> initialSelection,
                             boolean restart, Runnable onBack) {
        this(client, levelId, levelName, options, maxSeedSlots, previewZombies, levelWidth,
                levelHeight, sceneCells, initialSelection, restart, onBack, List.of(),
                null, List.of(), false);
    }

    /**
     * The whole screen, before the buff page existed.
     *
     * <p>Kept for the tests and callers that describe a board rather than a menu: a level with no
     * buffs offers none, which is exactly what every level did before this page existed.
     */
    public ChooseSeedsScreen(PvzceClient client, String levelId, String levelName,
                             List<SeedOption> options, int maxSeedSlots,
                             List<String> previewZombies, int levelWidth, int levelHeight,
                             List<SceneSyncS2C.Cell> sceneCells, List<String> initialSelection,
                             boolean restart, Runnable onBack, List<String> lockedSlots,
                             Identifier background, List<String> hiddenSceneElements,
                             boolean dealsItsOwnCards) {
        this(client, levelId, levelName, options, maxSeedSlots, previewZombies, levelWidth,
                levelHeight, sceneCells, initialSelection, restart, onBack, lockedSlots,
                background, hiddenSceneElements, dealsItsOwnCards, List.of(), 0, List.of(), null);
    }

    /**
     * @param lockedSlots slot ids the level fixes in the bar. They start selected and
     *                    cannot be removed; everything else the player may toggle, up to
     *                    {@code maxSeedSlots} slots in total.
     * @param dealsItsOwnCards true when the level's own card source hands the cards out (a
     *                    conveyor belt). Nothing here can be chosen, so the screen becomes the
     *                    same pass-through a fixed deck gets: the lawn, the level's opening
     *                    conversation and the zombies it will send, and then it starts itself.
     *                    Belt levels used to skip this screen entirely - and with it the only
     *                    place a level's zombie line-up is ever shown.
     * @param buffOptions the level buffs this level offers, as the server resolved them. Empty
     *                    for a level that does not offer any, which is what makes the buff page
     *                    appear only where a level opted in.
     * @param maxBuffSlots the resolved buff count ({@code LevelDef.effectiveMaxBuffSlots}), the
     *                    same number the server will accept - never recomputed here.
     * @param lockedBuffs the level's own buffs: on from the start and not removable, drawn with
     *                    the same padlock a fixed card gets.
     * @param initialBuffs what the buff page starts with - a resumed run's own list, otherwise
     *                    the world's auto list. {@code null} is not the same as empty: it means
     *                    "nobody ever chose", which is also what an empty list means here, so the
     *                    two collapse safely at this end.
     */
    public ChooseSeedsScreen(PvzceClient client, String levelId, String levelName,
                             List<SeedOption> options, int maxSeedSlots,
                             List<String> previewZombies, int levelWidth, int levelHeight,
                             List<SceneSyncS2C.Cell> sceneCells, List<String> initialSelection,
                             boolean restart, Runnable onBack, List<String> lockedSlots,
                             Identifier background, List<String> hiddenSceneElements,
                             boolean dealsItsOwnCards, List<SeedOption> buffOptions,
                             int maxBuffSlots, List<String> lockedBuffs, List<String> initialBuffs) {
        super(client);
        this.levelId = levelId;
        this.background = background;
        this.sceneVisibility = SceneVisibility.of(hiddenSceneElements);
        this.levelName = levelName;
        this.options = List.copyOf(options);
        this.maxSeedSlots = Math.max(0, maxSeedSlots);
        this.previewZombies = List.copyOf(previewZombies);
        this.levelWidth = Math.max(1, levelWidth);
        this.levelHeight = Math.max(1, levelHeight);
        this.scene = new String[this.levelWidth][this.levelHeight];
        for (int x = 0; x < this.levelWidth; x++) {
            for (int y = 0; y < this.levelHeight; y++) {
                this.scene[x][y] = "pvzce:grass";
            }
        }
        if (sceneCells != null) {
            for (SceneSyncS2C.Cell cell : sceneCells) {
                if (cell.x() >= 0 && cell.x() < this.levelWidth
                        && cell.y() >= 0 && cell.y() < this.levelHeight) {
                    this.scene[cell.x()][cell.y()] = cell.elementId();
                }
            }
        }
        this.restart = restart;
        this.onBack = onBack;
        // The level's cards first and always; then whatever was remembered or defaulted
        // into the slots it left over.
        if (lockedSlots != null) {
            for (String locked : lockedSlots) {
                if (locked != null && containsOption(locked)) {
                    this.lockedSlots.add(locked);
                    if (selectedOrder.size() < this.maxSeedSlots) {
                        selectedOrder.add(locked);
                    }
                }
            }
        }
        if (initialSelection != null) {
            Set<String> seen = new LinkedHashSet<>(selectedOrder);
            for (String seed : initialSelection) {
                if (selectedOrder.size() >= this.maxSeedSlots) {
                    break;
                }
                if (seen.add(seed) && containsOption(seed) && !isLocked(seed)) {
                    selectedOrder.add(seed);
                }
            }
        }
        // A level that deals its own cards has as little to choose here as one whose deck is
        // fixed: nothing the player picks would reach the bar.
        this.buffOptions = List.copyOf(buffOptions == null ? List.of() : buffOptions);
        this.maxBuffSlots = Math.max(0, maxBuffSlots);
        for (SeedOption option : this.buffOptions) {
            if (option.costSun() == com.pvzce.common.core.SeedOptions.LOCKED_OPTION) {
                lockedBuffOptions.add(option.slotId());
            }
        }
        // The level's own buffs first and always, exactly as its own cards are pinned: a buff the
        // level hands out is part of the level, not a suggestion the player may decline.
        if (lockedBuffs != null) {
            for (String locked : lockedBuffs) {
                if (locked != null && containsBuffOption(locked)) {
                    this.lockedBuffs.add(locked);
                    if (selectedBuffs.size() < this.maxBuffSlots) {
                        selectedBuffs.add(locked);
                    }
                }
            }
        }
        if (initialBuffs != null) {
            Set<String> seen = new LinkedHashSet<>(selectedBuffs);
            for (String buff : initialBuffs) {
                if (selectedBuffs.size() >= this.maxBuffSlots) {
                    break;
                }
                if (seen.add(buff) && containsBuffOption(buff) && !lockedBuffs.contains(buff)
                        && !lockedBuffOptions.contains(buff)) {
                    selectedBuffs.add(buff);
                }
            }
        }
        // A level that deals its own cards has as little to choose here as one whose deck is
        // fixed - but "nothing to choose" is now a statement about *both* pages: a level that
        // fixes its whole deck and still offers buffs is a real screen the player has to be able
        // to use, and auto-starting past it would silently drop their buffs.
        this.previewOnly = dealsItsOwnCards
                || (hasNothingToChoose() && !offersBuffChoice()
                        && selectedBuffs.isEmpty() && lockedBuffs.isEmpty());
    }

    private boolean containsBuffOption(String buffId) {
        for (SeedOption option : buffOptions) {
            if (option.slotId().equals(buffId)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsOption(String slotId) {
        for (SeedOption option : options) {
            if (option.slotId().equals(slotId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the buff page exists for this level.
     *
     * <p>Two conditions, and both matter: the level has to offer buffs at all (it listed
     * {@code pvzce:player_choice} - see {@code LevelDef.LevelBuffPlan}), and there has to be
     * something left to offer once its own fixed buffs are taken out.
     */
    private boolean offersBuffChoice() {
        return maxBuffSlots > 0 && buffOptions.size() > lockedBuffs.size();
    }

    /** The page the player is looking at: 0 is the card page, 1 the buff page. */
    private boolean onBuffPage() {
        return page == 1 && offersBuffChoice();
    }

    /**
     * The list the current page draws from.
     *
     * <p>One layout and one painter serve both pages, because the two pages are the same object
     * seen twice: a pool of things to pick, and a row of what has been picked. Only the list, the
     * caption and what "清空" clears differ.
     */
    private List<SeedOption> pageOptions() {
        return onBuffPage() ? buffOptions : options;
    }

    private List<String> pageSelection() {
        return onBuffPage() ? selectedBuffs : selectedOrder;
    }

    private int pageCapacity() {
        return onBuffPage() ? maxBuffSlots : maxSeedSlots;
    }

    private boolean isPageLocked(String id) {
        return onBuffPage() ? lockedBuffs.contains(id) : isLocked(id);
    }

    private void setPage(int next) {
        int clamped = Math.max(0, Math.min(1, next));
        if (clamped == page) {
            return;
        }
        page = clamped;
        // Flights belong to the page they started on; letting them cross pages would fly a card
        // into a row that is not showing it.
        flights.clear();
        gridScroll = 0F;
        updateLayout();
        playSound(SOUND_TAP, 1F, 1F);
    }

    @Override
    protected void init() {
        if (client.music() != null) {
            client.music().ensureMenu("pvzce:music/choose_your_seeds");
        }
        if (startNanos == 0L) {
            startNanos = System.nanoTime();
        }
        // The dialogue is built once and re-attached after a resize, so a window resize
        // mid-conversation does not start it over.
        if (dialogue == null) {
            dialogue = DialogueOverlay.create(client, client.levelDialogue(levelId), this::onDialogueFinished);
        }
        if (dialogue != null) {
            showDialog(dialogue);
        } else {
            panStartNanos = startNanos;
        }
        updateLayout();
        ensurePreviewAnimations();

        startButton = new Button((int) panelX + 10, (int) panelY + 10, 10, 10, "开始游戏", this::start)
                .style(Button.Style.SEED_CHOOSER);
        clearButton = new Button((int) panelX + 10, (int) panelY + 10, 10, 10, "清空", this::clearSelection)
                .style(Button.Style.SEED_CHOOSER);
        backButton = new Button(client.guiWidth() - 90, 4, 82, 28, "返回", this::requestClose)
                .style(Button.Style.SEED_CHOOSER);
        addWidget(startButton);
        addWidget(clearButton);
        addWidget(backButton);
        updateButtonState();
    }

    /**
     * Lays out the whole chooser. Card size only depends on the GUI size so a
     * pool packet always looks the same; the panel then shrinks to the width
     * of its widest card row (capped at {@value #PANEL_MAX_WIDTH_RATIO} of the
     * screen and floored at {@value #PANEL_MIN_WIDTH_RATIO}) while filling the
     * height left between the chosen-seed row and the screen bottom.
     */
    private void updateLayout() {
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        panelX = 0F;
        float margin = Math.max(4F, guiH * 0.008F);
        panelPad = Math.max(8F, Math.min(guiW * 0.018F, guiH * 0.035F));

        cardH = MathUtil.clamp(guiH * CARD_HEIGHT_RATIO, CARD_MIN_HEIGHT, CARD_MAX_HEIGHT);
        cardW = cardH * CARD_ASPECT;
        cardGap = MathUtil.clamp(cardW * 0.18F, 3F, 14F);
        sectionGap = cardGap * 1.7F;

        // Chosen seeds share the pool's card size and sit just above the panel.
        topSlots = Math.max(0, pageCapacity());
        float topRowWidth = topSlots <= 0 ? 0F : topSlots * (cardW + cardGap) - cardGap;
        float topRowScale = topRowWidth > 0F
                ? Math.min(1F, (guiW - margin * 2F) / topRowWidth) : 1F;
        topCardW = cardW * topRowScale;
        topCardH = cardH * topRowScale;
        topGap = cardGap * topRowScale;

        sections = buildSections();

        float maxPanelW = Math.max(180F, guiW * PANEL_MAX_WIDTH_RATIO);
        float minPanelW = Math.max(160F, guiW * PANEL_MIN_WIDTH_RATIO);
        float innerMaxW = Math.max(cardW, maxPanelW - panelPad * 2F);
        // The number of cards per row is never fixed: as many as fit in the
        // panel's width budget, then the frame shrinks back around them.
        columns = Math.max(1, (int) Math.floor((innerMaxW + cardGap) / (cardW + cardGap)));

        float widestRow = 0F;
        float gridHeight = 0F;
        for (int i = 0; i < sections.size(); i++) {
            CardSection section = sections.get(i);
            int perRow = Math.min(section.indices.size(), columns);
            section.rows = (section.indices.size() + columns - 1) / columns;
            section.height = section.rows * cardH + (section.rows - 1) * cardGap;
            widestRow = Math.max(widestRow, perRow * cardW + Math.max(0, perRow - 1) * cardGap);
            gridHeight += section.height;
            if (i > 0) {
                gridHeight += sectionGap;
            }
        }
        panelW = MathUtil.clamp(widestRow + panelPad * 2F, minPanelW, maxPanelW);

        // Bottom action row: clear on the left, start on the right.
        float buttonH = MathUtil.clamp(cardH * 0.5F, 18F, 42F);
        float startW = MathUtil.clamp(panelW * 0.36F, 56F, 160F);
        float clearW = MathUtil.clamp(panelW * 0.24F, 42F, 120F);
        float buttonsGap = Math.max(6F, panelW * 0.04F);
        float buttonTotal = startW + clearW + buttonsGap;
        float buttonAvailable = panelW - panelPad * 2F;
        if (buttonTotal > buttonAvailable && buttonTotal > 0F) {
            float factor = buttonAvailable / buttonTotal;
            startW *= factor;
            clearW *= factor;
        }
        startButtonW = startW;
        startButtonH = buttonH;
        clearButtonW = clearW;
        clearButtonH = buttonH;
        startButtonX = panelX + panelW - panelPad - startW;
        clearButtonX = panelX + panelPad;

        float gapGridTop = Math.max(6F, cardH * 0.14F);
        float gapButtonsGrid = Math.max(6F, cardH * 0.22F);

        float topRowH = topSlots > 0 ? topCardH : 0F;
        float topRowGap = topSlots > 0 ? Math.max(6F, guiH * 0.012F) : 0F;
        float availableH = Math.max(1F, guiH - margin * 2F - topRowH - topRowGap);

        // The board covers the whole left column under the chosen-seed row so
        // no backdrop shows through beneath it. The pool inside stays compact
        // and top-aligned, which leaves the lower part of the board as plain
        // wood instead of stretching the block gaps apart.
        panelY = margin;
        panelH = availableH;
        float panelTop = panelY + panelH;

        // The title and the counter sit on the frame's top bar rather than in
        // the dark interior, so the card pool can start right below the frame.
        // The bar is the 20px top slice plus the first rows of the tiled wood.
        float panelScale = Math.min(panelH / PANEL_NATIVE_HEIGHT, panelW / PANEL_NATIVE_WIDTH);
        float topBarH = PANEL_BAR_NATIVE * panelScale;
        titleScale = Math.min(MathUtil.clamp(guiH / 240F, 0.66F, 1.30F),
                Math.max(0.25F, topBarH * 0.78F / 18F));
        float titleLineH = client.fonts().body().lineHeight(titleScale);
        titleY = panelTop - topBarH + (topBarH - titleLineH) / 2F;
        // Text has to clear the gold ornament tiled into the bar's left end.
        titleInset = Math.max(panelPad, PANEL_ORNAMENT_RIGHT * panelScale + 8F);

        float innerLeft = panelX + panelPad;
        float innerBottom = panelY + panelPad;
        startButtonY = innerBottom;
        clearButtonY = innerBottom;

        float gridRegionTop = panelTop - topBarH - gapGridTop;
        float gridRegionBottom = innerBottom + buttonH + gapButtonsGrid;
        float gridRegionH = Math.max(1F, gridRegionTop - gridRegionBottom);
        gridContentH = gridHeight;
        // A sub-pixel shortfall must not pop a scrollbar into existence.
        float overflow = gridHeight - gridRegionH;
        gridMaxScroll = overflow > 1F ? overflow : 0F;
        gridScroll = MathUtil.clamp(gridScroll, 0F, gridMaxScroll);
        gridViewTop = gridRegionTop;
        // With room to spare the pool ends above the action row; the clip has
        // to follow the grid so that last row is not cut off.
        gridViewBottom = gridMaxScroll > 0F
                ? gridRegionBottom
                : Math.min(gridRegionBottom, gridRegionTop - gridHeight);

        // Blocks keep their compact spacing and hang from the title bar; the
        // first one hugs the frame, the last one hugs the action buttons.
        float contentTop = gridMaxScroll > 0F ? gridRegionTop + gridScroll : gridRegionTop;
        gridX = innerLeft;
        float cursor = contentTop;
        for (CardSection section : sections) {
            section.top = cursor;
            cursor -= section.height + sectionGap;
        }

        topBarX = innerLeft;
        topBarY = panelTop + topRowGap;

        layoutTabs(panelTop, topBarH, panelScale);
    }

    /**
     * Places the two page tabs on the frame's title bar.
     *
     * <p>Centred on the bar, and only when there is room for them: the title sits on the left and
     * the counter on the right, so a narrow panel would have the three overlapping. When they do
     * not fit, the tabs move up to the strip above the frame - the panel is what shrinks, and the
     * page switch is the one control that must never become unreachable. A level with no buff page
     * gets no tabs at all rather than one disabled tab.
     */
    private void layoutTabs(float panelTop, float topBarH, float panelScale) {
        tabsVisible = offersBuffChoice();
        for (float[] rect : tabRects) {
            java.util.Arrays.fill(rect, 0F);
        }
        if (!tabsVisible) {
            return;
        }
        float tabH = MathUtil.clamp(topBarH * 0.62F, 14F, 30F);
        float tabW = MathUtil.clamp(panelW * 0.21F, 52F, 116F);
        float gap = Math.max(2F, panelScale * 6F);
        float total = tabW * 2F + gap;
        float x = panelX + (panelW - total) / 2F;
        float y = panelTop - topBarH + (topBarH - tabH) / 2F;
        // The title's own inset is the left edge it starts at, so the tabs may use everything
        // to the right of it - and the counter needs about a third of the bar.
        float titleEnd = panelX + titleInset + 60F * titleScale;
        float counterStart = panelX + panelW - titleInset - 80F
                * MathUtil.clamp(panelW / 360F, 0.58F, 0.9F);
        if (x < titleEnd || x + total > counterStart) {
            y = panelTop + Math.max(2F, topBarH * 0.12F);
        }
        tabRects[0][0] = x;
        tabRects[0][1] = y;
        tabRects[0][2] = tabW;
        tabRects[0][3] = tabH;
        tabRects[1][0] = x + tabW + gap;
        tabRects[1][1] = y;
        tabRects[1][2] = tabW;
        tabRects[1][3] = tabH;
    }

    /** Draws the two page tabs; the active one is lit. */
    private void drawTabs(float shift, float alpha) {
        if (!tabsVisible) {
            return;
        }
        String[] labels = {"种子卡槽", "关卡增益"};
        for (int i = 0; i < 2; i++) {
            float[] rect = tabRects[i];
            boolean active = page == i;
            client.drawSolid(rect[0] + shift, rect[1], rect[2], rect[3], 0.5F,
                    active ? 0.92F : 0.42F, active ? 0.80F : 0.36F, active ? 0.44F : 0.20F,
                    (active ? 0.96F : 0.78F) * alpha);
            float scale = MathUtil.clamp(rect[3] / 22F, 0.5F, 0.86F);
            String label = labels[i];
            client.fonts().body().draw(label,
                    rect[0] + shift + (rect[2] - client.fonts().body().width(label, scale)) / 2F,
                    rect[1] + (rect[3] - client.fonts().body().lineHeight(scale)) / 2F,
                    scale, active ? 0.10F : 0.88F, active ? 0.08F : 0.84F,
                    active ? 0.04F : 0.70F, alpha);
        }
    }

    /**
     * Splits the level's pool into one block per card kind. Blocks are ordered
     * resource → tool → plant: plants are usually the largest block and read
     * best at the bottom, while the frequently used tools stay near the top.
     * Inside a block the level's own slot order is preserved.
     */
    private List<CardSection> buildSections() {
        List<SeedOption> pool = pageOptions();
        List<CardSection> result = new ArrayList<>();
        for (int i = 0; i < pool.size(); i++) {
            String kind = pool.get(i).kind();
            String normalized = kind == null ? "" : kind;
            CardSection target = null;
            for (CardSection section : result) {
                if (section.kind.equals(normalized)) {
                    target = section;
                    break;
                }
            }
            if (target == null) {
                target = new CardSection(normalized);
                result.add(target);
            }
            target.indices.add(i);
        }
        result.sort(Comparator.comparingInt(section -> sectionRank(section.kind)));
        return List.copyOf(result);
    }

    /** Pool block order: resources, then the frequently used tools, then plants. */
    private static int sectionRank(String kind) {
        if (KIND_RESOURCE.equals(kind)) {
            return 0;
        }
        if (KIND_TOOL.equals(kind)) {
            return 1;
        }
        if (KIND_PLANT.equals(kind)) {
            return 2;
        }
        return 3;
    }

    /**
     * Chosen-row order: resources, then plants, then tools on the far right.
     * This intentionally differs from {@link #sectionRank(String)} so the row
     * matches the in-level card bar, where the shovel/hammer stay rightmost.
     */
    private static int barRank(String kind) {
        if (KIND_RESOURCE.equals(kind)) {
            return 0;
        }
        if (KIND_PLANT.equals(kind)) {
            return 1;
        }
        if (KIND_TOOL.equals(kind)) {
            return 2;
        }
        if (KIND_BUFF.equals(kind)) {
            return 3;
        }
        return 4;
    }

    /**
     * The chosen seeds in display order: resources first, then plants, then
     * tools, each group keeping the order the player clicked them in. Mirrors
     * the in-level card bar so the two never disagree.
     */
    private List<String> orderedSelection() {
        return orderedSelection(pageSelection());
    }

    /**
     * The given selection in display order: resources first, then plants, then tools, each group
     * keeping the order the player clicked them in.
     *
     * <p>Takes the list rather than reading a field so both pages share it - the buff page's row
     * is ordered by the same rule (a buff's kind ranks last, so a level that offered both would
     * still read cards first).
     */
    private List<String> orderedSelection(List<String> selection) {
        List<String> ordered = new ArrayList<>(selection.size());
        for (int rank = 0; rank <= 4; rank++) {
            for (String slotId : selection) {
                SeedOption option = optionById(slotId);
                String kind = option == null ? "" : option.kind();
                if (barRank(kind) == rank) {
                    ordered.add(slotId);
                }
            }
        }
        return ordered;
    }

    private void updateButtonState() {
        float progress = panelProgress();
        float shift = panelCurrentX() - panelX;
        // A preview-only screen has no panel for them to belong to, so they never appear
        // even though they still exist: the exit animation reads their position.
        boolean visible = !previewOnly && progress > 0.92F;
        if (startButton != null) {
            startButton.setVisible(visible);
            startButton.setPosition(Math.round(startButtonX + shift), Math.round(startButtonY));
            startButton.setSize(Math.max(1, Math.round(startButtonW)), Math.max(1, Math.round(startButtonH)));
        }
        if (clearButton != null) {
            clearButton.setVisible(visible);
            clearButton.setPosition(Math.round(clearButtonX + shift), Math.round(clearButtonY));
            clearButton.setSize(Math.max(1, Math.round(clearButtonW)), Math.max(1, Math.round(clearButtonH)));
        }
        if (backButton != null) {
            backButton.setVisible(!previewOnly && progress > 0.5F);
        }
    }

    @Override
    public void requestClose() {
        // ESC and 返回 are the only exits while the dialogue is up (its clicks advance the
        // conversation instead), so they skip the rest of it rather than leaving a level
        // the player only just chose. The screen stays open; the normal flow resumes.
        if (dialogueActive()) {
            dialogue.skipAll();
            return;
        }
        // Release the previews on every exit path, including the custom back action.
        releasePreviewAnimations();
        if (onBack != null) {
            onBack.run();
        } else {
            super.requestClose();
        }
    }

    /**
     * Empties the page the player is looking at.
     *
     * <p>Per page rather than both at once: the button sits under whichever list is on screen,
     * and a player who clears their cards has not asked to lose the buffs they chose on the other
     * tab. The level's own cards and buffs are never cleared - they are part of the level.
     */
    private void clearSelection() {
        if (onBuffPage()) {
            if (selectedBuffs.size() <= lockedBuffs.size()) {
                return;
            }
            selectedBuffs.removeIf(id -> !lockedBuffs.contains(id));
        } else {
            if (selectedOrder.size() <= lockedSlots.size()) {
                return;
            }
            selectedOrder.removeIf(id -> !isLocked(id));
        }
        flights.removeIf(flight -> !isPageLocked(flight.option.slotId()));
        playSound(SOUND_TAP, 1F, 1F);
    }

    /** Adds or removes one buff, with the same rules a card follows. */
    private void toggleBuff(SeedOption option) {
        String id = option.slotId();
        if (lockedBuffs.contains(id) || lockedBuffOptions.contains(id)) {
            // A fixed buff cannot be switched off, and one the player has not been given cannot
            // be switched on. Both answer with the same tap rather than a reason, which is what
            // a fixed card already does.
            playSound(SOUND_TAP, 1F, 1F);
            return;
        }
        if (selectedBuffs.contains(id)) {
            selectedBuffs.remove(id);
            flights.removeIf(flight -> flight.option.slotId().equals(id));
            playSound(SOUND_TAP, 1F, 1F);
            return;
        }
        if (selectedBuffs.size() >= maxBuffSlots) {
            playSound(SOUND_TAP, 1F, 1F);
            return;
        }
        List<SeedOption> pool = pageOptions();
        int index = pool.indexOf(option);
        if (index < 0) {
            return;
        }
        selectedBuffs.add(id);
        float[] from = cardCenter(index);
        flights.add(new Flight(option, from[0], from[1]));
        playSound(SOUND_SEEDLIFT, 1F, 1F);
    }

    private boolean isLocked(String slotId) {
        return lockedSlots.contains(slotId);
    }

    /**
     * Requests the start, after the exit animation.
     *
     * <p>Used to send the packet immediately, so the level appeared between two
     * frames with the wooden panel still on screen - the "生硬" jump. Now the panel
     * slides back out and the camera pans home first, and {@link #tick} sends the
     * packet when that beat is over. The delay is a constant rather than a callback
     * so backing out cannot leave a pending start behind.
     */
    /**
     * The 开始游戏 button.
     *
     * <p>A bar with no sun card in it is the one choice this page can make that the player
     * cannot fix afterwards: nothing on the lawn collects sun without that card, and on most
     * levels the sky alone does not pay for a defence. So the first click asks - once - and the
     * player who meant it (a conveyor level, a level that pays for kills) starts anyway.
     *
     * <p>Asked here rather than in {@link #finishStart} because the exit animation plays on the
     * first click: by the time the packet is due the player has watched the level start, and a
     * question at that point is a question about something already happening.
     */
    private void start() {
        if (exitNanos != 0L || startSent) {
            return;
        }
        if (!onBuffPage() && !hasSunCard()) {
            client.currentScreen().showDialog(ConfirmDialog.startWithoutSun(client, this::beginStart, null));
            return;
        }
        beginStart();
    }

    /** True when the bar the player is about to start with has the sun card in it. */
    private boolean hasSunCard() {
        for (String slotId : orderedSelection()) {
            if (SUN_CARD_ID.equals(slotId)) {
                return true;
            }
        }
        return false;
    }

    /** Plays the exit animation and, once it is over, sends the start packet. */
    private void beginStart() {
        if (exitNanos != 0L || startSent) {
            return;
        }
        exitNanos = System.nanoTime();
    }

    /** Sends the start packet exactly once, when the exit animation has played out. */
    private void finishStart() {
        if (startSent || exitNanos == 0L || exitProgress() < 1F) {
            return;
        }
        startSent = true;
        // The card row in bar order and the buff row in its own order: the server re-sorts
        // nothing, and both lists become what the run starts with (and, for buffs, what this
        // world pre-selects next time).
        client.startLevelWithSeedsAndBuffs(levelId, restart, new ArrayList<>(orderedSelection(selectedOrder)),
                new ArrayList<>(selectedBuffs));
    }

    /**
     * True when the level leaves the player nothing to choose.
     *
     * <p>Either every slot is pinned by the level, or the backpack has nothing left to
     * offer. Both mean the same thing to the player: no cards to pick, so there is no page
     * to show - see {@link #previewOnly}.
     */
    private boolean hasNothingToChoose() {
        return com.pvzce.common.core.SeedOptions.hasNothingToChoose(options, maxSeedSlots, lockedSlots);
    }

    private void toggleOption(SeedOption option) {
        if (onBuffPage()) {
            toggleBuff(option);
            return;
        }
        String id = option.slotId();
        if (isLocked(id)) {
            // Already in the bar and staying there; clicking it is a no-op, not a remove.
            playSound(SOUND_TAP, 1F, 1F);
            return;
        }
        if (selectedOrder.contains(id)) {
            selectedOrder.remove(id);
            flights.removeIf(flight -> flight.option.slotId().equals(id));
            playSound(SOUND_TAP, 1F, 1F);
            return;
        }
        if (selectedOrder.size() >= maxSeedSlots) {
            playSound(SOUND_TAP, 1F, 1F);
            return;
        }
        int index = options.indexOf(option);
        if (index < 0) {
            return;
        }
        selectedOrder.add(id);
        float[] from = cardCenter(index);
        flights.add(new Flight(option, from[0], from[1]));
        playSound(SOUND_SEEDLIFT, 1F, 1F);
    }

    private void playSound(String soundId, float volume, float pitch) {
        if (client.sound() != null) {
            client.sound().play(soundId, volume, pitch);
        }
    }

    private float panelProgress() {
        if (exitNanos != 0L) {
            return 1F - MathUtil.easeOutCubic(exitProgress());
        }
        if (dialogueActive()) {
            return 0F;
        }
        long elapsed = System.nanoTime() - panStart();
        return MathUtil.easeOutCubic(MathUtil.clamp01((elapsed - PANEL_DELAY_NANOS) / (float) PANEL_SLIDE_NANOS));
    }

    private float panProgress() {
        if (exitNanos != 0L) {
            return 1F - MathUtil.easeInOut(exitProgress());
        }
        if (dialogueActive()) {
            return 0F;
        }
        long elapsed = System.nanoTime() - panStart();
        return MathUtil.easeInOut(MathUtil.clamp01(elapsed / (float) PAN_NANOS));
    }

    /**
     * When the pan/panel timeline starts: the dialogue's end when there is one, otherwise
     * the moment the screen opened.
     *
     * <p>The zombie preview is deliberately <em>not</em> on this clock - it walks in while
     * the character is still talking, which is what makes the scene behind a dialogue look
     * like the level rather than a still image.
     */
    private long panStart() {
        return panStartNanos != 0L ? panStartNanos : startNanos;
    }

    private boolean dialogueActive() {
        return dialogue != null && dialogue.isActive();
    }

    /** The conversation is over: the normal entry choreography takes it from here. */
    private void onDialogueFinished() {
        if (panStartNanos == 0L) {
            panStartNanos = System.nanoTime();
        }
    }

    /** 0..1 through the exit beat; the start packet goes out when it reaches 1. */
    private float exitProgress() {
        return MathUtil.clamp01((System.nanoTime() - exitNanos) / (float) exitDurationNanos());
    }

    /** How long the exit beat lasts: panel and camera together, or just the camera. */
    private long exitDurationNanos() {
        return previewOnly ? PREVIEW_EXIT_NANOS : EXIT_NANOS;
    }

    private float previewProgress() {
        long elapsed = System.nanoTime() - startNanos;
        return MathUtil.clamp01((elapsed - PREVIEW_DELAY_NANOS) / (float) PREVIEW_FADE_NANOS);
    }

    private float panelCurrentX() {
        float progress = panelProgress();
        return panelX - (1F - progress) * (panelW + 48F);
    }

    /** Center of a pool card in screen space, honouring scroll and slide-in. */
    private float[] cardCenter(int optionIndex) {
        float shift = panelCurrentX() - panelX;
        for (CardSection section : sections) {
            int local = section.indices.indexOf(optionIndex);
            if (local < 0) {
                continue;
            }
            int column = local % columns;
            int row = local / columns;
            float x = gridX + shift + column * (cardW + cardGap) + cardW / 2F;
            float y = section.top - cardH - row * (cardH + cardGap) + cardH / 2F;
            return new float[]{x, y};
        }
        return new float[]{gridX + shift + cardW / 2F, gridViewBottom + cardH / 2F};
    }

    private float[] topSlotCenter(int index, float shift) {
        float x = topBarX + shift + index * (topCardW + topGap) + topCardW / 2F;
        float y = topBarY + topCardH / 2F;
        return new float[]{x, y};
    }

    @Override
    public void tick() {
        updateButtonState();
        long now = System.nanoTime();
        flights.removeIf(flight -> now - flight.startNanos >= FLY_NANOS);
        if (exitNanos != 0L) {
            finishStart();
            return;
        }
        // A fixed deck shows the preview and then starts itself. The countdown is from the
        // pan, not from the screen opening, so a dialogue cannot eat into the player's look
        // at the lane.
        if (!dialogueActive() && previewOnly && now - panStart() >= AUTO_START_NANOS) {
            start();
        }
    }

    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        for (AbstractWidget widget : widgets) {
            if (widget.isMouseOver(guiX, guiY)) {
                return;
            }
        }
        // Preview-only: there is no panel, no pool and no chosen row, so every click is a
        // click on the lawn. Nothing to do.
        if (previewOnly || button != 0 || panelProgress() < 0.92F) {
            return;
        }

        // The tabs first: they sit on the frame, above everything the page draws.
        if (tabsVisible) {
            float shift = panelCurrentX() - panelX;
            for (int i = 0; i < 2; i++) {
                float[] rect = tabRects[i];
                if (guiX >= rect[0] + shift && guiX <= rect[0] + shift + rect[2]
                        && guiY >= rect[1] && guiY <= rect[1] + rect[3]) {
                    setPage(i);
                    return;
                }
            }
        }

        // The chosen-seed row floats above the panel and removes on click.
        if (topSlots > 0 && guiY >= topBarY && guiY <= topBarY + topCardH) {
            float shift = panelCurrentX() - panelX;
            float localTopX = (float) guiX - (topBarX + shift);
            int index = (int) Math.floor(localTopX / (topCardW + topGap));
            float inSlotX = localTopX - index * (topCardW + topGap);
            List<String> ordered = orderedSelection();
            if (index >= 0 && index < ordered.size()
                    && inSlotX >= 0F && inSlotX <= topCardW) {
                String removed = ordered.get(index);
                if (isLocked(removed)) {
                    // A fixed card is part of the level, not a choice; clicking it does
                    // nothing rather than silently dropping the level's setup.
                    playSound(SOUND_TAP, 1F, 1F);
                    return;
                }
                selectedOrder.remove(removed);
                flights.clear();
                playSound(SOUND_TAP, 1F, 1F);
            }
            return;
        }

        if (guiY < gridViewBottom || guiY > gridViewTop) {
            return;
        }
        int index = optionAt(guiX, guiY);
        if (index >= 0) {
            toggleOption(pageOptions().get(index));
        }
    }

    /**
     * Names the pool card or buff under the cursor.
     *
     * <p>The chooser showed pictures and nothing else: a player who did not already know what
     * {@code scaredy_shroom} looked like had no way to find out before spending a slot on it.
     * Skipped while the panel is still arriving and while the pointer is over the chosen row -
     * that row's entries are already picked, so the tip would be answering a question nobody
     * asked.
     */
    private void drawPoolHover(float alpha) {
        if (previewOnly || alpha < 0.92F || dialogueActive()) {
            return;
        }
        double mouseX = client.guiMouseX(client.window().cursorX());
        double mouseY = client.guiMouseY(client.window().cursorY());
        if (mouseY >= topBarY && mouseY <= topBarY + topCardH) {
            return;
        }
        int index = optionAt(mouseX, mouseY);
        if (index < 0) {
            return;
        }
        SeedOption option = pageOptions().get(index);
        com.pvzce.client.gui.HoverTip.draw(client,
                com.pvzce.client.gui.HoverTip.nameOf(option.slotId(), option.kind()),
                (float) mouseX, (float) mouseY, alpha);
    }

    /**
     * Names the page tab under the cursor, so a two-word label needs no guessing.
     *
     * <p>Drawn last of all and only while the panel is fully in: it is a tooltip on a control
     * that is already labelled, and it must not sit under a flying card.
     */
    private void drawTabHover(float currentPanelX, float alpha) {
        if (!tabsVisible || panelProgress() < 0.92F) {
            return;
        }
        float shift = currentPanelX - panelX;
        double guiX = client.guiMouseX(client.window().cursorX());
        double guiY = client.guiMouseY(client.window().cursorY());
        String[] hints = {"选择这一局带的卡片", "选择这一局的关卡增益"};
        for (int i = 0; i < 2; i++) {
            float[] rect = tabRects[i];
            if (guiX < rect[0] + shift || guiX > rect[0] + shift + rect[2]
                    || guiY < rect[1] || guiY > rect[1] + rect[3]) {
                continue;
            }
            com.pvzce.client.gui.HoverTip.draw(client, hints[i], (float) guiX, (float) guiY, alpha);
            return;
        }
    }

    /** Index into the current page's pool of the card under the cursor, or -1. */
    private int optionAt(double guiX, double guiY) {
        float shift = panelCurrentX() - panelX;
        float localX = (float) guiX - gridX - shift;
        if (localX < 0F) {
            return -1;
        }
        int column = (int) Math.floor(localX / (cardW + cardGap));
        float cellX = localX - column * (cardW + cardGap);
        if (column < 0 || column >= columns || cellX > cardW) {
            return -1;
        }
        for (CardSection section : sections) {
            if (guiY > section.top || guiY < section.top - section.height) {
                continue;
            }
            float localY = section.top - (float) guiY;
            int row = (int) Math.floor(localY / (cardH + cardGap));
            if (row < 0 || row >= section.rows) {
                return -1;
            }
            if (localY - row * (cardH + cardGap) > cardH) {
                return -1;
            }
            int slot = row * columns + column;
            if (slot < 0 || slot >= section.indices.size()) {
                return -1;
            }
            return section.indices.get(slot);
        }
        return -1;
    }

    @Override
    protected void onMouseScrolled(double guiX, double guiY, double amount) {
        boolean overPool = gridMaxScroll > 0F
                && guiX >= panelX && guiX <= panelX + panelW
                && guiY >= gridViewBottom && guiY <= gridViewTop;
        if (!overPool) {
            return;
        }
        int delta = amount > 0 ? -1 : 1;
        float next = gridScroll + delta * (cardH + cardGap);
        float clamped = MathUtil.clamp(next, 0F, gridMaxScroll);
        if (Math.abs(clamped - gridScroll) > 0.001F) {
            gridScroll = clamped;
            updateLayout();
        }
    }

    @Override
    public void render() {
        client.beginGuiView();
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        updateButtonState();

        LevelStage.Stage stage = LevelStage.cover(guiW, guiH);
        // Home is where the level itself will be looking: the game camera shows the
        // covered backdrop centred, so its left edge sits at stage.x() and the visible part
        // is the middle of the image. Panning to stage.x() - panMax, which is what this
        // used to do, framed the far end of the house instead - the view jumped a quarter
        // of a screen sideways the moment the chooser handed over to the level.
        float panMax = Math.max(0F, (stage.width() - guiW) / 2F);
        float panOffset = panProgress() * panMax;
        float stageX = stage.x() - panOffset;
        LevelStage.Board board = LevelStage.board(guiW, guiH, levelWidth, levelHeight);

        // The board is drawn the way the level will open, night levels included: this screen
        // is a preview of the board, so a 2-1 that turned dark only after "开始游戏" was
        // previewing a different level. The tint follows the board across the pan because the
        // glow is mapped into the board's own pixels.
        client.applyLevelLighting(levelId, board.x() + (stageX - stage.x()), board.y(),
                board.cellWidth(), board.cellHeight());
        client.drawTexture(background == null ? LevelStage.BACKGROUND_TEXTURE : background,
                stageX, stage.y(),
                stage.width(), stage.height(), -1F, 1F, 1F, 1F, 1F);

        drawSceneLawn(board, stageX);
        drawZombiePreview(stage, stageX, previewProgress());
        // Everything from here on is UI - the wooden panel, the cards, the top bar, the
        // conversation over it - and a night tint on those would be a filter on the interface
        // rather than on the lawn.
        client.beginGuiView();

        float panelProgress = panelProgress();
        float currentPanelX = panelCurrentX();
        if (!previewOnly && panelProgress > 0F) {
            drawSeedPanel(currentPanelX, panelProgress);
            if (panelProgress > 0.92F) {
                float shift = currentPanelX - panelX;
                drawTopBar(shift, panelProgress);
                drawFlights(shift);
                // Last of the panel's own furniture so a card flying into the chosen row cannot
                // cover the control that switches pages.
                drawTabs(shift, panelProgress);
            }
            if (panelProgress > 0.55F) {
                drawPoolHover(panelProgress);
                drawTabHover(currentPanelX, panelProgress);
            }
        }

        for (AbstractWidget widget : widgets) {
            if (dialogueActive() && widget != dialogue) {
                // The panel is still sliding out - it is simply not this screen's turn
                // any more. Drawing the buttons over the character would offer the
                // player controls that cannot be clicked.
                continue;
            }
            widget.render(client);
        }
    }

    private void drawSceneLawn(LevelStage.Board board, float stageX) {
        // The pan is the whole change of frame: the board travels with the backdrop.
        float panShift = stageX - LevelStage.cover(client.guiWidth(), client.guiHeight()).x();
        float boardX = board.x() + panShift;
        client.clipping().push(boardX, board.y(), board.width(), board.height());
        try {
            SceneTileRenderer.renderBoard(client, levelWidth, levelHeight, this::sceneAt,
                    boardX, board.y(), board.cellWidth(), board.cellHeight(), sceneVisibility);
        } finally {
            client.clipping().pop();
        }
    }

    private String sceneAt(int x, int y) {
        if (x < 0 || x >= levelWidth || y < 0 || y >= levelHeight) {
            return "pvzce:grass";
        }
        return scene[x][y];
    }

    private void drawZombiePreview(LevelStage.Stage stage, float stageX, float alpha) {
        if (alpha <= 0F || previewEntities.isEmpty()) {
            return;
        }
        float roadImageX = 1010F;
        float roadImageRight = 1390F;
        float roadImageTop = 30F;
        float roadImageBottom = 570F;
        float previewX = stageX + roadImageX * stage.scale();
        float previewY = stage.y() + (LevelStage.IMAGE_HEIGHT - roadImageBottom) * stage.scale();
        float previewW = Math.max(80F, (roadImageRight - roadImageX) * stage.scale());
        float previewH = Math.max(120F, (roadImageBottom - roadImageTop) * stage.scale());

        int count = previewEntities.size();
        float spacing = 1.35F;
        float requiredHeight = Math.max(1.8F, (count - 1) * spacing + 1.8F);
        float aspect = previewW / previewH;
        float worldHeight = Math.max(2.4F, requiredHeight);
        float worldWidth = Math.max(1.8F, worldHeight * aspect);
        worldHeight = Math.max(requiredHeight, worldWidth / aspect);
        float worldBottom = -0.3F;
        float worldTop = worldBottom + worldHeight;
        float offsetY = (worldHeight - requiredHeight) / 2F;

        client.beginOverlayWorldView(previewX, previewY, previewW, previewH,
                -worldWidth / 2F, worldWidth / 2F, worldBottom, worldTop);
        for (int i = 0; i < count; i++) {
            ClientEntity entity = previewEntities.get(i);
            float cellY = worldBottom + offsetY + i * spacing;
            entity.update(0F, cellY, entity.health(), "idle", 0.36F);
            if (!client.animations().render(entity)) {
                // Same resolver the in-game card bar uses: a modded entity's sprite has to be
                // its own namespace on both screens, and "textures/entities/<path>" is only the
                // convention for content that declares no texture of its own.
                Identifier texture = com.pvzce.client.renderer.EntityTextures.forEntity(entity.defId());
                client.drawTexture(texture, -0.35F, cellY, 0.7F, 1.0F, 0.4F, 1F, 1F, 1F, alpha);
            }
        }
        client.beginGuiView();

        float labelScale = Math.max(0.45F, Math.min(0.62F, previewH / Math.max(1, count) / 90F));
        for (int i = 0; i < count; i++) {
            float cellY = worldBottom + offsetY + i * spacing;
            float labelY = previewY + ((cellY - worldBottom) / worldHeight) * previewH;
            client.fonts().body().draw(shortId(previewEntities.get(i).defIdString()), previewX + 4F, labelY,
                    labelScale, 1F, 1F, 1F, alpha);
        }
    }

    /**
     * Drops the preview playbacks. Without this every visit to the seed chooser left
     * up to ten strongly-referenced entities in the animation manager, and they were
     * advanced on every frame for the rest of the session.
     */
    private void releasePreviewAnimations() {
        if (client.animations() == null) {
            return;
        }
        for (ClientEntity entity : previewEntities) {
            client.animations().release(entity);
        }
        previewEntities.clear();
        previewAnimationsReady = false;
    }

    private void ensurePreviewAnimations() {
        if (previewAnimationsReady) {
            return;
        }
        previewAnimationsReady = true;
        if (client.animations() == null || previewZombies.isEmpty()) {
            return;
        }
        int count = Math.min(previewZombies.size(), 10);
        for (int i = 0; i < count; i++) {
            ClientEntity entity = new ClientEntity(900_000 + i, "zombie", previewZombies.get(i),
                    0F, 0F, 100, com.pvzce.api.entity.EntityLayers.GROUND,
                    com.pvzce.api.entity.EntityAnimations.IDLE, 0.36F, "pvzce:zombie_team");
            entity.attachAnimationManager(client.animations());
            entity.update(0F, 0F, 100, "idle", 0.36F);
            entity.playAnimation("idle");
            previewEntities.add(entity);
        }
    }

    private void drawSeedPanel(float currentPanelX, float alpha) {
        float shift = currentPanelX - panelX;
        float panelScale = Math.min(panelH / PANEL_NATIVE_HEIGHT, panelW / PANEL_NATIVE_WIDTH);
        NinePatch.drawNineSliceTiled(client, PANEL_BACKGROUND, currentPanelX, panelY, panelW, panelH, 0.1F,
                PANEL_NATIVE_WIDTH, PANEL_NATIVE_HEIGHT,
                PANEL_BORDER, PANEL_BORDER, PANEL_BORDER, PANEL_BORDER,
                panelScale, 1F, 1F, 1F, alpha);

        boolean buffPage = onBuffPage();
        String title = buffPage ? "关卡增益" : "选择你的种子";
        client.fonts().button().draw(title, currentPanelX + titleInset, titleY,
                titleScale, 1F, 0.95F, 0.8F, alpha);

        // Fixed cards count towards the total, so the hint names them: otherwise the
        // player sees "3/6" on a fresh screen and cannot tell why. The buff page counts the
        // same way, for the same reason: a level may hand out buffs the player never picked.
        Set<String> locked = buffPage ? lockedBuffs : lockedSlots;
        int chosen = buffPage ? selectedBuffs.size() : selectedOrder.size();
        int capacity = buffPage ? maxBuffSlots : maxSeedSlots;
        String counter = locked.isEmpty()
                ? "已选 " + chosen + "/" + capacity
                : "已选 " + chosen + "/" + capacity
                        + "（锁定 " + locked.size() + "）";
        float counterScale = MathUtil.clamp(panelW / 360F, 0.58F, 0.9F);
        client.fonts().body().draw(counter,
                currentPanelX + panelW - titleInset - client.fonts().body().width(counter, counterScale),
                titleY, counterScale, 1F, 0.95F, 0.62F, alpha);

        // A level that fixes its whole deck starts itself; saying so (rather than
        // showing a button nobody has to press) is what keeps the auto-start from
        // reading as the screen closing on its own.
        if (hasNothingToChoose() && !buffPage) {
            String hint = "本关卡组固定，即将开始";
            float hintScale = MathUtil.clamp(panelW / 420F, 0.5F, 0.72F);
            client.fonts().body().draw(hint, currentPanelX + titleInset, titleY - client.fonts().body().lineHeight(hintScale) - 2F,
                    hintScale, 1F, 0.88F, 0.5F, alpha);
        }

        if (levelName != null && !levelName.isBlank()) {
            float levelScale = MathUtil.clamp(panelW / 420F, 0.5F, 0.75F);
            float available = startButtonX - (clearButtonX + clearButtonW) - 8F;
            if (available >= 24F) {
                float width = client.fonts().body().width(levelName, levelScale);
                if (width > available) {
                    levelScale *= available / Math.max(1F, width);
                    width = client.fonts().body().width(levelName, levelScale);
                }
                client.fonts().body().draw(levelName,
                        (clearButtonX + clearButtonW + startButtonX) / 2F + shift - width / 2F,
                        startButtonY + (startButtonH - client.fonts().body().lineHeight(levelScale)) / 2F,
                        levelScale, 0.9F, 0.82F, 0.62F, alpha);
            }
        }

        drawPoolCards(currentPanelX, alpha);
        drawPoolScrollbar(currentPanelX, alpha);
    }

    /** Pool cards, clipped to the scrollable grid region between title and buttons. */
    private void drawPoolCards(float currentPanelX, float alpha) {
        if (sections.isEmpty()) {
            return;
        }
        float shift = currentPanelX - panelX;
        client.clipping().push(currentPanelX + panelPad, gridViewBottom,
                panelW - panelPad * 2F, gridViewTop - gridViewBottom);
        try {
            List<SeedOption> pool = pageOptions();
            List<String> chosenIds = pageSelection();
            for (CardSection section : sections) {
                for (int row = 0; row < section.rows; row++) {
                    for (int column = 0; column < columns; column++) {
                        int slot = row * columns + column;
                        if (slot >= section.indices.size()) {
                            break;
                        }
                        SeedOption option = pool.get(section.indices.get(slot));
                        float x = gridX + shift + column * (cardW + cardGap);
                        float y = section.top - cardH - row * (cardH + cardGap);
                        boolean chosen = chosenIds.contains(option.slotId());
                        drawCard(option, x, y, cardW, cardH, chosen, alpha);
                    }
                }
            }
        } finally {
            client.clipping().pop();
        }
    }

    /** Thin thumb shown only when the pool is taller than the panel. */
    private void drawPoolScrollbar(float currentPanelX, float alpha) {
        if (gridMaxScroll <= 0F) {
            return;
        }
        float trackW = Math.max(3F, panelPad * 0.28F);
        float trackX = currentPanelX + panelW - panelPad * 0.78F;
        float trackH = gridViewTop - gridViewBottom;
        client.drawSolid(trackX, gridViewBottom, trackW, trackH, 0.25F, 0F, 0F, 0F, 0.35F * alpha);
        float thumbH = Math.max(trackW * 3F, trackH * (trackH / Math.max(1F, gridContentH)));
        float travel = Math.max(0F, trackH - thumbH);
        float t = gridMaxScroll <= 0F ? 0F : gridScroll / gridMaxScroll;
        client.drawSolid(trackX, gridViewTop - thumbH - t * travel, trackW, thumbH,
                0.26F, 0.93F, 0.83F, 0.5F, alpha);
    }

    private void drawTopBar(float shift, float alpha) {
        if (topSlots <= 0) {
            return;
        }
        List<String> ordered = orderedSelection();
        float x0 = topBarX + shift;
        float rowWidth = topSlots * (topCardW + topGap) - topGap;
        client.drawSolid(x0 - 4F, topBarY - 4F, rowWidth + 8F, topCardH + 8F,
                0.2F, 0F, 0F, 0F, 0.5F * alpha);
        for (int i = 0; i < topSlots; i++) {
            float x = x0 + i * (topCardW + topGap);
            client.drawTexture(CARD_BACKGROUND, x, topBarY, topCardW, topCardH, 0.15F,
                    0.45F, 0.45F, 0.45F, alpha);
            if (i >= ordered.size()) {
                continue;
            }
            String slotId = ordered.get(i);
            if (hasActiveFlight(slotId)) {
                continue;
            }
            SeedOption option = optionById(slotId);
            if (option != null) {
                // Chosen packets never dim; the dimmed copy is the pool card.
                drawCard(option, x, topBarY, topCardW, topCardH, false, alpha);
            }
        }
    }

    private void drawFlights(float shift) {
        if (flights.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        List<String> ordered = orderedSelection();
        Iterator<Flight> iterator = flights.iterator();
        while (iterator.hasNext()) {
            Flight flight = iterator.next();
            float progress = MathUtil.clamp01((now - flight.startNanos) / (float) FLY_NANOS);
            if (progress >= 1F) {
                iterator.remove();
                continue;
            }
            int targetIndex = ordered.indexOf(flight.option.slotId());
            if (targetIndex < 0) {
                iterator.remove();
                continue;
            }
            float eased = MathUtil.easeOutCubic(progress);
            float[] target = topSlotCenter(targetIndex, shift);
            float x = MathUtil.lerp(flight.startX, target[0], eased);
            float y = MathUtil.lerp(flight.startY, target[1], eased);
            y += (float) Math.sin(Math.PI * progress) * 26F;
            float size = cardW + (topCardW - cardW) * eased;
            float height = cardH + (topCardH - cardH) * eased;
            drawCard(flight.option, x - size / 2F, y - height / 2F, size, height, false, 1F);
        }
    }

    private boolean hasActiveFlight(String slotId) {
        for (Flight flight : flights) {
            if (flight.option.slotId().equals(slotId)) {
                return true;
            }
        }
        return false;
    }

    private SeedOption optionById(String slotId) {
        for (SeedOption option : options) {
            if (option.slotId().equals(slotId)) {
                return option;
            }
        }
        for (SeedOption option : buffOptions) {
            if (option.slotId().equals(slotId)) {
                return option;
            }
        }
        return null;
    }

    /**
     * Draws one seed packet. {@code chosen} dims the packet (the original PvZ
     * way of marking an already-picked pool card) and never draws a border:
     * the chosen row above the panel passes {@code false} to stay bright.
     */
    private void drawCard(SeedOption option, float x, float y, float width, float height,
                          boolean chosen, float alpha) {
        float brightness = chosen ? CHOSEN_BRIGHTNESS : 1F;
        if (SUN_CARD_ID.equals(option.slotId())) {
            drawSunBankCard(x, y, width, height, brightness, alpha);
            return;
        }
        SeedCardRenderer.draw(client, new SeedCardRenderer.CardModel(
                        iconFor(option),
                        SeedCardRenderer.CardKind.fromJson(option.kind()),
                        option.costSun(), brightness, alpha, true, 0F, false, null, false),
                x, y, width, height);
        if (isPageLocked(option.slotId()) || lockedBuffOptions.contains(option.slotId())) {
            drawLockBadge(x, y, width, height, alpha);
        }
    }

    /**
     * The sprite a pool entry draws: the option's own icon, or the buff's.
     *
     * <p>A buff's icon is deliberately not in the payload - the sprite belongs to the buff and
     * the server would be echoing data it does not otherwise use. The client has the same
     * definition the buff's behaviour comes from, so it asks that. A buff nobody registered
     * falls through to the missing-texture tile rather than to a blank card, which is the same
     * thing an unknown card icon does.
     */
    private Identifier iconFor(SeedOption option) {
        Identifier icon = Identifier.tryParse(option.icon());
        if (icon != null) {
            return icon;
        }
        Identifier buffId = Identifier.tryParse(option.slotId());
        com.pvzce.api.content.LevelBuff buff =
                buffId == null ? null : com.pvzce.common.buff.LevelBuffs.get(buffId);
        if (buff == null || buff.icon().isEmpty()) {
            return null;
        }
        return buff.icon().texture();
    }

    /**
     * Marks a card the level fixes in the bar.
     *
     * <p>A fixed card is drawn as chosen (it is in the bar from the start) and is not
     * removable, so without a mark it looks like a card the player simply happened to
     * pick. The badge is a filled corner plus a horizontal bar - the padlock a player
     * reads instantly, drawn from primitives because the UI has no icon font.
     */
    private void drawLockBadge(float x, float y, float width, float height, float alpha) {
        float size = Math.max(10F, Math.min(width, height) * 0.34F);
        client.drawSolid(x + width - size, y, size, size, 0.4F, 0.15F, 0.16F, 0.2F, 0.85F * alpha);
        // Body.
        float bodyW = size * 0.56F;
        float bodyH = size * 0.42F;
        float bodyX = x + width - size / 2F - bodyW / 2F;
        float bodyY = y + size * 0.18F;
        client.drawSolid(bodyX, bodyY, bodyW, bodyH, 0.45F, 1F, 0.86F, 0.35F, alpha);
        // Shackle: two uprights and a top bar, so it reads as a padlock at this size.
        float legW = Math.max(1F, bodyW * 0.16F);
        float shackleH = size * 0.26F;
        client.drawSolid(bodyX + bodyW * 0.16F, bodyY + bodyH, legW, shackleH, 0.45F,
                1F, 0.86F, 0.35F, alpha);
        client.drawSolid(bodyX + bodyW * 0.68F, bodyY + bodyH, legW, shackleH, 0.45F,
                1F, 0.86F, 0.35F, alpha);
        client.drawSolid(bodyX + bodyW * 0.16F, bodyY + bodyH + shackleH, bodyW * 0.68F,
                Math.max(1F, legW * 0.8F), 0.45F, 1F, 0.86F, 0.35F, alpha);
    }

    /**
     * The sun slot is not a normal seed packet: selecting it turns on the
     * in-level SunBank HUD, so the chooser shows the bank itself (aspect-fit)
     * instead of a packet with a resource icon.
     */
    private void drawSunBankCard(float x, float y, float width, float height,
                                 float brightness, float alpha) {
        float bankH = height * 0.94F;
        float bankW = bankH * 78F / 87F;
        if (bankW > width * 0.94F) {
            bankW = width * 0.94F;
            bankH = bankW * 87F / 78F;
        }
        client.drawTexture(SUN_BANK, x + (width - bankW) / 2F, y + (height - bankH) / 2F,
                bankW, bankH, 0.2F, brightness, brightness, brightness, alpha);
    }

    private static String path(String id) {
        return id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
    }

    private static String shortId(String id) {
        return com.pvzce.client.gui.GuiText.shortId(id);
    }
}
