package com.pvzce.client.gui.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.content.GameRuleType;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.LevelRewards;
import com.pvzce.api.util.Identifier;
import com.pvzce.api.util.LevelGrouping;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.GuiText;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.AbstractWidget;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.EditBox;
import com.pvzce.client.gui.components.PaletteList;
import com.pvzce.client.gui.components.Slider;
import com.pvzce.client.renderer.EntityTextures;
import com.pvzce.client.renderer.EntityVisuals;
import com.pvzce.client.renderer.LevelStage;
import com.pvzce.client.renderer.SceneTileRenderer;
import com.pvzce.client.renderer.SpriteRenderer;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.network.packet.CommandC2S;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Level editor.
 *
 * <p>Layout is one page per concern (terrain / plants / zombies / rules / waves /
 * cards / music / level info) instead of a single canvas surrounded by every
 * control the editor owns. The previous screen packed the palette, a category
 * switcher, board resize buttons, a day/night toggle and six action buttons around
 * a canvas that took whatever space was left, and its wave editor was a dialog
 * holding three competing lists.
 *
 * <p>The board is drawn through the game's own world projection
 * ({@code beginOverlayWorldView}) rather than as GUI-space quads. That is what lets
 * a preset plant or zombie play its real animation - the editor hands the animation
 * manager the same {@link ClientEntity} shape the board does - and it also means
 * the editor's cells cannot drift away from the in-game board's geometry, because
 * both call {@link LevelStage#board} for their footprint.
 *
 * <p>Everything the editor does not understand survives a save: the level JSON is
 * kept as loaded and only the edited fields are overwritten, so opening and
 * re-saving a level authored elsewhere cannot silently drop its teams, unlock lists
 * or a field a later version added.
 */
public final class EditorScreen extends Screen {
    /** One page of the editor. The first three share the palette on the left. */
    private enum Page {
        TERRAIN("terrain", PaletteList.Kind.SCENE),
        PLANT("plant", PaletteList.Kind.ENTITY),
        ZOMBIE("zombie", PaletteList.Kind.ENTITY),
        RULE("rule", null),
        WAVE("wave", null),
        CARDS("cards", null),
        MUSIC("music", null),
        UNLOCK("unlock", null),
        INFO("info", null);

        private final String key;
        private final PaletteList.Kind paletteKind;

        Page(String key, PaletteList.Kind paletteKind) {
            this.key = key;
            this.paletteKind = paletteKind;
        }

        String label() {
            return GuiLang.raw("pvzce.editor.page." + key, key);
        }
    }

    private static final Page[] PAGES = Page.values();
    private static final int MAX_COLUMNS = 20;
    private static final int MAX_ROWS = 10;

    /**
     * Slider ranges per rule path.
     *
     * <p>{@link GameRuleType} clamps values but does not expose its bounds, and the
     * editor must not offer a range the server would silently clamp afterwards.
     * These mirror the registrations in {@code BuiltInRegistries.registerGameRules};
     * a rule with no entry here falls back to a 0..1 slider that still writes
     * whatever the author types.
     */
    private static final Map<String, float[]> RULE_RANGES = Map.ofEntries(
            Map.entry("pvzce:day_length", new float[]{0F, 12000F}),
            Map.entry("pvzce:night_length", new float[]{-1F, 12000F}),
            Map.entry("pvzce:sun_spawn_chance", new float[]{0F, 1F}),
            Map.entry("pvzce:sun_value", new float[]{1F, 500F}),
            Map.entry("pvzce:crater_recovery", new float[]{0F, 30000F}),
            Map.entry("pvzce:zombie_damage_multiplier", new float[]{0F, 5F}),
            Map.entry("pvzce:zombie_speed_multiplier", new float[]{0F, 5F}),
            Map.entry("pvzce:plant_damage_multiplier", new float[]{0F, 5F}),
            Map.entry("pvzce:max_players_per_team", new float[]{1F, 64F}));

    /** Rules whose value is a whole number of ticks or players; the slider rounds. */
    private static final Set<String> INTEGER_RULES = Set.of(
            "pvzce:day_length", "pvzce:night_length", "pvzce:crater_recovery",
            "pvzce:sun_value", "pvzce:max_players_per_team");

    /** The order the rule page lists them in: pacing, economy, combat, then the rest. */
    private static final List<String> RULE_ORDER = List.of(
            "pvzce:day_length", "pvzce:night_length", "pvzce:sun_spawn_chance", "pvzce:sun_value",
            "pvzce:crater_recovery", "pvzce:grave_spawn_night", "pvzce:zombie_damage_multiplier",
            "pvzce:zombie_speed_multiplier", "pvzce:plant_damage_multiplier",
            "pvzce:max_players_per_team", "pvzce:level_pause_on_single_player");

    /**
     * The level's id, which is also its theme/category path.
     *
     * <p>Not final: the info page can move a level to another theme/category, and that is a
     * rename of the id. It is the only operation that changes one - the id field itself is
     * read-only, because saves and other levels refer to it.
     */
    private Identifier levelId;
    /** Where to write on save; {@code null} until resolved for a brand-new level. */
    private Path sourceFile;
    /** The JSON as loaded, so unedited and unknown fields survive a save. */
    private JsonObject sourceJson = new JsonObject();

    private String levelName;
    private String description = "";
    private int initialSun = 150;

    // --- unlock conditions (the "解锁" page) ------------------------------------
    /** Prerequisite level ids, comma separated; the same shape the text box edits. */
    private String unlockLevels = "";
    /** Required card ids, comma separated. */
    private String unlockCards = "";
    /** Coin price for buying the level outright; 0 means it cannot be bought. */
    private int unlockCost;
    /** Keep the level out of the list until its conditions are met. */
    private boolean unlockHidden;
    private EditBox unlockLevelsBox;
    private EditBox unlockCardsBox;
    private EditBox unlockCostBox;
    private Button unlockHiddenButton;
    /** Requirements the page cannot edit, kept verbatim across a save. */
    private JsonArray unlockExtra = new JsonArray();
    private int width = 9;
    private int height = 5;
    private final Map<String, List<String>> scene = new LinkedHashMap<>();
    private final List<JsonObject> initialEntities = new ArrayList<>();
    private final JsonObject rules = new JsonObject();
    private final WaveEditorModel.Config waveConfig = new WaveEditorModel.Config();
    private final MusicEditorModel.Config musicConfig = new MusicEditorModel.Config();
    private final CardPoolEditorDialog.Config cardPoolConfig = new CardPoolEditorDialog.Config();
    private int maxSeedSlots = LevelDef.DEFAULT_MAX_SEED_SLOTS;

    private Page page = Page.TERRAIN;
    private String status = "";
    private long statusUntilNanos;

    // Layout, recomputed by init().
    private int navY;
    private int navH;
    private int actionY;
    private int actionH;
    private int paletteX;
    private int paletteW;
    private int centerX;
    private int centerY;
    private int centerW;
    private int centerH;
    private int sideX;
    private int sideW;

    private PaletteList palette;
    private final List<AbstractWidget> pageWidgets = new ArrayList<>();
    private final List<CanvasEntity> canvasEntities = new ArrayList<>();

    private String selectedSceneId = "pvzce:grass";
    private String selectedEntityId = "";

    // Page-owned widgets.
    private AbstractSelectionList<Identifier> ruleList;
    private Slider ruleSlider;
    private EditBox ruleValueBox;
    private Button ruleBoolButton;
    private Identifier currentRule;
    private AbstractSelectionList<String> fixedList;
    private AbstractSelectionList<String> poolList;
    private AbstractSelectionList<AvailableRow> availableList;
    /** Where the card page's three lists begin; its labels sit in the strip above. */
    private int cardListTop;

    private AbstractSelectionList<WaveEditorModel.WaveModel> waveList;
    private Button waveTypeButton;
    private EditBox waveDelayBox;
    private EditBox waveWarningBox;
    /** Ticks between this wave's zombies; empty means the engine's default. */
    private EditBox waveIntervalBox;
    private EditBox waveCountBox;
    private PaletteList waveEntryList;
    private PaletteList waveZombieList;
    private AbstractSelectionList<MusicEditorModel.CueModel> musicCueList;
    private AbstractSelectionList<String> musicEventList;
    private Button musicTrackButton;
    private Button musicLoopButton;
    private Button musicStopButton;
    private EditBox musicTickBox;
    private EditBox musicVolumeBox;
    private EditBox musicFadeBox;
    private EditBox nameBox;
    private EditBox descriptionBox;
    private EditBox sunBox;
    /**
     * The rewards block's four editable numbers.
     *
     * <p>Kept as fields, not only as text boxes, because the info page is rebuilt on
     * every resize and a half-typed box must not become the saved value.
     */
    private EditBox rewardUnlockBox;
    private EditBox repeatCoinsBox;
    private EditBox coinDropChanceBox;
    private EditBox coinDropAmountBox;
    private EditBox coinDropBox;
    private String rewardUnlock = "";
    private int rewardRepeatCoins = LevelRewards.DEFAULT_REPEAT_COINS;
    private float rewardCoinDropChance = LevelRewards.DEFAULT_COIN_DROP_CHANCE;
    private String rewardCoinDrop = LevelRewards.DEFAULT_COIN_DROP.toString();
    private int rewardCoinDropAmount = LevelRewards.DEFAULT_COIN_DROP_AMOUNT;

    /** A preset entity on the board, backed by a {@link ClientEntity} so it animates. */
    private static final class CanvasEntity {
        private final JsonObject json;
        private final ClientEntity entity;

        CanvasEntity(JsonObject json, ClientEntity entity) {
            this.json = json;
            this.entity = entity;
        }

        int x() {
            return json.get("x").getAsInt();
        }

        int y() {
            return json.get("y").getAsInt();
        }

        String id() {
            return json.get("id").getAsString();
        }

        String kind() {
            String kind = json.has("kind") ? json.get("kind").getAsString() : "plant";
            return kind.startsWith("z") ? "zombie" : "plant";
        }
    }

    public EditorScreen(PvzceClient client, Identifier levelId, String levelName, int width, int height) {
        super(client);
        this.levelId = levelId;
        this.levelName = levelName;
        this.width = Math.max(1, Math.min(MAX_COLUMNS, width));
        this.height = Math.max(1, Math.min(MAX_ROWS, height));
        loadExisting();
    }

    /** Opens an existing level for editing, preserving its id. */
    public static EditorScreen forExisting(PvzceClient client, Identifier levelId) {
        LevelDef def = BuiltInRegistries.LEVELS.get(levelId);
        String name = def == null ? levelId.path() : def.displayName();
        int w = def == null ? 9 : def.width();
        int h = def == null ? 5 : def.height();
        return new EditorScreen(client, levelId, name, w, h);
    }

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    /**
     * Reads the level's JSON and, when it came from the user-level pack, remembers the
     * file it came from.
     *
     * <p>The file is located by listing {@code data/<ns>/levels/} and matching the
     * {@code id} inside each file, rather than by assembling a path from the id. A path
     * cannot be derived from a level id in general: the loader lets an explicit
     * {@code "id"} override the one implied by the file path, and a level may live in a
     * nested directory. Assembling one is what this used to do - it produced
     * {@code data/pvzce/levels/<path>} with no
     * {@code .json} - so loading a level you had just saved always found nothing and the
     * editor opened it as an empty new level.
     */
    private void loadExisting() {
        LevelSource source = findLevelSource();
        if (source != null) {
            sourceJson = source.json();
            sourceFile = source.writableFile();
        }
        boolean loaded = !sourceJson.entrySet().isEmpty();
        if (loaded) {
            boolean hasSlots = sourceJson.has("slots");
            width = clamp(sourceJson.has("width") ? sourceJson.get("width").getAsInt() : width, 1, MAX_COLUMNS);
            height = clamp(sourceJson.has("height") ? sourceJson.get("height").getAsInt() : height, 1, MAX_ROWS);
            if (sourceJson.has("name") && !sourceJson.get("name").getAsString().isBlank()) {
                levelName = sourceJson.get("name").getAsString();
            }
            description = sourceJson.has("description") ? sourceJson.get("description").getAsString() : "";
            initialSun = sourceJson.has("initial_sun") ? sourceJson.get("initial_sun").getAsInt() : 150;
            readRewards(sourceJson);
            readUnlock(sourceJson);
            if (sourceJson.has("scene")) {
                for (var entry : sourceJson.getAsJsonObject("scene").entrySet()) {
                    List<String> positions = new ArrayList<>();
                    for (JsonElement element : entry.getValue().getAsJsonArray()) {
                        positions.add(element.getAsString());
                    }
                    scene.put(entry.getKey(), positions);
                }
            }
            if (sourceJson.has("initial_entities")) {
                for (JsonElement element : sourceJson.getAsJsonArray("initial_entities")) {
                    initialEntities.add(element.getAsJsonObject().deepCopy());
                }
            }
            if (sourceJson.has("rules")) {
                for (var entry : sourceJson.getAsJsonObject("rules").entrySet()) {
                    rules.add(entry.getKey(), entry.getValue());
                }
            }
            waveConfig.replaceWith(WaveEditorModel.Config.fromJson(sourceJson));
            cardPoolConfig.replaceWith(CardPoolEditorDialog.Config.fromJson(sourceJson));
            musicConfig.replaceWith(MusicEditorModel.Config.fromJson(sourceJson));
            refreshCardPool(!hasSlots);
        } else {
            // A brand-new level: one explicit grasswalk cue and the default card pool,
            // so it is playable as soon as it is saved.
            MusicEditorModel.CueModel cue = new MusicEditorModel.CueModel();
            cue.event = "pvzce:music/grasswalk";
            musicConfig.cues.add(cue);
            refreshCardPool(true);
        }

        scene.putIfAbsent("pvzce:grass", new ArrayList<>());
        maxSeedSlots = Math.max(0, cardPoolConfig.maxSeedSlots);
        syncCanvasEntities();
    }

    /** A level's JSON and, when the editor may write it, the file it lives in. */
    private record LevelSource(JsonObject json, Path writableFile) {
    }

    /**
     * The highest-priority copy of this level's JSON, plus the writable file when the
     * copy came from the user-level pack.
     *
     * <p>Walks the pack stack so a user override of a built-in level is what gets
     * edited. A level that only exists inside the built-in pack is still editable - its
     * save writes a user-level override of the same id.
     */
    private LevelSource findLevelSource() {
        String prefix = "data/" + levelId.namespace() + "/levels/";
        try {
            java.util.Map<String, com.pvzce.common.resource.PackResource> found =
                    client.resources().listResources("data");
            // listResources keeps the highest-priority occurrence, so one pass is enough.
            Path userPack = client.gameDir().resolve("datapacks/user_levels");
            for (java.util.Map.Entry<String, com.pvzce.common.resource.PackResource> entry : found.entrySet()) {
                String path = entry.getKey();
                if (!path.startsWith(prefix) || !path.endsWith(".json")) {
                    continue;
                }
                JsonObject json;
                try {
                    json = JsonParser.parseString(entry.getValue().readString()).getAsJsonObject();
                } catch (RuntimeException e) {
                    continue;
                }
                // The id in the file wins over the path, so match on it.
                Identifier declared = json.has("id")
                        ? Identifier.tryParse(json.get("id").getAsString())
                        : Identifier.tryParse(levelId.namespace() + ":"
                                + path.substring(prefix.length(), path.length() - ".json".length()));
                if (!levelId.equals(declared)) {
                    continue;
                }
                Path writable = userPack.resolve(path);
                return new LevelSource(json, Files.exists(writable) ? writable : null);
            }
        } catch (Exception e) {
            System.err.println("[PVZCE] Could not read level " + levelId + " for the editor: " + e.getMessage());
        }
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Keeps the editor's card pool and "all slots" list in sync with the registries. */
    private void refreshCardPool(boolean fillDefaults) {
        Set<String> available = new LinkedHashSet<>();
        BuiltInRegistries.SLOT_TYPES.keySet().stream()
                .map(Identifier::toString)
                .sorted()
                .forEach(available::add);
        available.addAll(cardPoolConfig.pool);
        cardPoolConfig.available.clear();
        cardPoolConfig.available.addAll(available);
        if (fillDefaults && cardPoolConfig.pool.isEmpty()) {
            for (String slot : defaultLevelCards()) {
                if (available.contains(slot)) {
                    cardPoolConfig.pool.add(slot);
                }
            }
        }
    }

    /**
     * The cards a new level starts with, in bar order.
     *
     * <p>Both "new level" and the card page's "恢复默认卡" use this list, so the two cannot
     * drift into different ideas of what a default deck is. It is deliberately shorter than
     * the slot count: the slots it leaves over are where the player gets to choose.
     */
    private static List<String> defaultLevelCards() {
        return List.of("pvzce:pea_shooter", "pvzce:sunflower", "pvzce:wall_nut",
                "pvzce:kernel_pult", "pvzce:sun", "pvzce:shovel");
    }

    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        pageWidgets.clear();
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        int margin = 10;

        navH = Math.max(24, Math.min(32, guiH / 20));
        navY = guiH - margin - navH;
        actionH = Math.max(26, Math.min(34, guiH / 18));
        actionY = margin;

        int sideWidth = Math.max(140, Math.min(280, guiW / 5));
        sideX = guiW - margin - sideWidth;
        sideW = sideWidth;
        paletteX = margin;
        paletteW = Math.max(140, Math.min(250, guiW / 6));
        centerX = paletteX + paletteW + margin;
        centerW = Math.max(120, sideX - margin - centerX);
        centerY = actionY + actionH + margin;
        // 22px are reserved above the panel for the page label; without it the label
        // and the pages' own labels were drawn into the nav bar.
        centerH = Math.max(80, navY - margin - centerY - 6 - 22);

        buildNav();
        buildActionBar();
        buildPage();
    }

    private void buildNav() {
        int gap = 4;
        int buttonW = Math.max(44, (client.guiWidth() - 20 - gap * (PAGES.length - 1)) / PAGES.length);
        List<Button> buttons = new ArrayList<>();
        for (int i = 0; i < PAGES.length; i++) {
            Page target = PAGES[i];
            Button button = new Button(10 + i * (buttonW + gap), navY, buttonW, navH, target.label(),
                    () -> switchPage(target));
            buttons.add(button);
        }
        navButtons = buttons;
        for (Button button : buttons) {
            addWidget(button);
        }
        refreshNavState();
    }

    private List<Button> navButtons = new ArrayList<>();

    private void refreshNavState() {
        for (int i = 0; i < navButtons.size() && i < PAGES.length; i++) {
            navButtons.get(i).setActive(PAGES[i] != page);
        }
    }

    /** Total width the action buttons occupy; the header starts after them. */
    private int actionButtonSpan;

    /**
     * Save, test and back - the level actions, and nothing else.
     *
     * <p>"编辑波次" and "音乐" used to sit here as shortcuts into a dialog and a second
     * screen, duplicating pages that already exist in the navigation bar. Two entries for
     * one destination is how the pages stayed half-built: the wave page was three buttons
     * and an empty panel because the real editing was one menu deeper.
     */
    private void buildActionBar() {
        int w = Math.max(72, Math.min(120, client.guiWidth() / 10));
        actionButtonSpan = (w + 6) * 3;
        addWidget(new Button(paletteX, actionY, w, actionH, GuiLang.raw("pvzce.save", "保存"), this::save));
        addWidget(new Button(paletteX + w + 6, actionY, w, actionH, GuiLang.raw("pvzce.test", "测试"), this::test));
        addWidget(new Button(paletteX + (w + 6) * 2, actionY, w, actionH,
                GuiLang.raw("pvzce.back", "返回"), client::closeScreen));
    }

    /**
     * The rectangle the pages that need the whole width use: palette plus centre plus
     * side panel. The wave and music editors have two columns of their own and read
     * badly squeezed into the centre column.
     *
     * <p>The band is taller than {@link #centerH} by the 22px reserved above the panel for
     * the page label. A whole-width page draws its own heading inside its first row, so
     * that reserve is dead space for it - and at 720p, where the whole band is barely 400px,
     * four labelled fields did not fit without it.
     */
    private int[] fullContentArea() {
        return new int[]{paletteX, centerY, sideX + sideW - paletteX, centerH + 22};
    }

    private void switchPage(Page target) {
        if (page == target) {
            return;
        }
        page = target;
        for (AbstractWidget widget : pageWidgets) {
            widgets.remove(widget);
        }
        pageWidgets.clear();
        buildPage();
        refreshNavState();
    }

    private <T extends AbstractWidget> T own(T widget) {
        pageWidgets.add(widget);
        addWidget(widget);
        return widget;
    }

    private void buildPage() {
        palette = null;
        ruleList = null;
        ruleSlider = null;
        ruleValueBox = null;
        ruleBoolButton = null;
        fixedList = null;
        poolList = null;
        availableList = null;
        waveList = null;
        waveTypeButton = null;
        waveDelayBox = null;
        waveWarningBox = null;
        waveIntervalBox = null;
        waveCountBox = null;
        waveEntryList = null;
        waveZombieList = null;
        musicCueList = null;
        musicEventList = null;
        musicTrackButton = null;
        musicLoopButton = null;
        musicStopButton = null;
        musicTickBox = null;
        musicVolumeBox = null;
        musicFadeBox = null;
        nameBox = null;
        descriptionBox = null;
        sunBox = null;
        rewardUnlockBox = null;
        repeatCoinsBox = null;
        coinDropChanceBox = null;
        coinDropAmountBox = null;
        coinDropBox = null;
        unlockLevelsBox = null;
        unlockCardsBox = null;
        unlockCostBox = null;
        unlockHiddenButton = null;

        if (page.paletteKind != null) {
            buildPalette(page.paletteKind);
        }
        switch (page) {
            case TERRAIN, PLANT, ZOMBIE -> buildCanvasSidePanel();
            case RULE -> buildRulePage();
            case WAVE -> buildWavePage();
            case CARDS -> buildCardPage();
            case MUSIC -> buildMusicPage();
            case UNLOCK -> buildUnlockPage();
            case INFO -> buildInfoPage();
        }
    }

    private void buildPalette(PaletteList.Kind kind) {
        int rowH = clamp(centerH / 10, 32, 48);
        palette = own(new PaletteList(paletteX, centerY, paletteW, centerH, rowH, kind));
        List<PaletteList.Item> items = switch (kind) {
            // Grass last, i.e. at the bottom row: the list is drawn bottom-up, and the
            // bottom row is the one nearest the board an author is painting.
            case SCENE -> java.util.stream.Stream.concat(
                            BuiltInRegistries.SCENE_ELEMENTS.keySet().stream()
                                    .sorted()
                                    .filter(id -> !"grass".equals(id.path())),
                            java.util.stream.Stream.of(Identifier.withDefaultNamespace("grass")))
                    .map(id -> PaletteList.Item.of(client, PaletteList.Kind.SCENE, id, null))
                    .toList();
            case ENTITY -> (page == Page.ZOMBIE ? BuiltInRegistries.ZOMBIES.keySet()
                    : BuiltInRegistries.PLANTS.keySet()).stream()
                    .sorted()
                    .map(id -> PaletteList.Item.of(client, PaletteList.Kind.ENTITY, id, null))
                    .toList();
            default -> List.of();
        };
        palette.setItems(items);
        if (!items.isEmpty()) {
            palette.select(0);
            selectFromPalette(items.get(0));
        }
    }

    private void selectFromPalette(PaletteList.Item item) {
        if (item == null || item.id() == null || palette == null) {
            return;
        }
        PaletteList.Kind kind = palette.kind();
        if (kind == PaletteList.Kind.SCENE) {
            selectedSceneId = item.id().toString();
        } else if (kind == PaletteList.Kind.ENTITY) {
            selectedEntityId = item.id().toString();
        }
    }

    private void buildCanvasSidePanel() {
        int rowH = clamp(centerH / 14, 26, 34);
        int buttonW = Math.max(56, (sideW - 8) / 2);
        int top = centerY + centerH - rowH;
        own(new Button(sideX, top, buttonW, rowH, "宽 -", () -> resize(width - 1, height)));
        own(new Button(sideX + buttonW + 8, top, buttonW, rowH, "宽 +", () -> resize(width + 1, height)));
        own(new Button(sideX, top - rowH - 6, buttonW, rowH, "高 -", () -> resize(width, height - 1)));
        own(new Button(sideX + buttonW + 8, top - rowH - 6, buttonW, rowH, "高 +", () -> resize(width, height + 1)));
        own(new Button(sideX, top - (rowH + 6) * 2, sideW, rowH,
                GuiLang.raw("pvzce.editor.clear_entities", "清空实体"), this::clearEntities));
    }

    private void resize(int newWidth, int newHeight) {
        int clampedW = clamp(newWidth, 1, MAX_COLUMNS);
        int clampedH = clamp(newHeight, 1, MAX_ROWS);
        if (clampedW == width && clampedH == height) {
            return;
        }
        width = clampedW;
        height = clampedH;
        // Preset entities outside the new bounds would be invisible and unfixable.
        initialEntities.removeIf(entity -> entity.get("x").getAsInt() >= width
                || entity.get("y").getAsInt() >= height);
        syncCanvasEntities();
    }

    private void clearEntities() {
        releaseCanvasAnimations();
        initialEntities.clear();
        syncCanvasEntities();
    }

    // ------------------------------------------------------------------
    // Rule page
    // ------------------------------------------------------------------

    private static List<Identifier> orderedRules() {
        List<Identifier> ids = new ArrayList<>();
        for (String path : RULE_ORDER) {
            Identifier id = Identifier.tryParse(path);
            if (id != null && BuiltInRegistries.GAME_RULES.get(id) != null) {
                ids.add(id);
            }
        }
        BuiltInRegistries.GAME_RULES.keySet().stream()
                .sorted()
                .filter(id -> !ids.contains(id))
                .forEach(ids::add);
        return ids;
    }

    private void buildRulePage() {
        // One control row, then the list above it. The controls must sit inside the
        // panel - a slider parked below centerY was half cut off by the panel edge at
        // every window size - and the row is shared so numeric and boolean rules never
        // show two sets of controls at once.
        int panelW = centerW + sideW + 10;
        int controlH = clamp(centerH / 14, 28, 36);
        int pad = 8;
        int rowY = centerY + pad + 22;
        int listBottom = rowY + controlH + 10;
        int listH = Math.max(70, centerY + centerH - listBottom - 12);
        int rowH = clamp(listH / 7, 30, 46);

        ruleList = own(new AbstractSelectionList<>(centerX, listBottom, panelW, listH,
                rowH, (renderClient, id, rx, ry) -> {
        }));
        ruleList.setEntryRenderer(this::renderRuleRow);
        ruleList.setEntries(orderedRules());

        int boxW = Math.max(90, Math.min(130, panelW / 5));
        // A slider alone cannot express 0..12000 ticks; the field is the precise input
        // and the slider the coarse one, and both write the same rule.
        ruleValueBox = own(new EditBox(centerX + pad, rowY, boxW, controlH, this::applyRuleBox));
        ruleValueBox.setValueChangedListener(this::applyRuleBox);
        ruleSlider = own(new Slider(centerX + pad + boxW + 8, rowY,
                Math.max(120, panelW - boxW - 36), controlH, 0F, 1F, 0F, slider -> applyRuleSlider()));
        ruleBoolButton = own(new Button(centerX + pad, rowY, Math.max(200, panelW - 24), controlH,
                GuiLang.raw("pvzce.editor.rule_default", "默认"), this::toggleRuleBoolean));
        int resetW = Math.max(120, panelW / 4);
        own(new Button(centerX + panelW - pad - resetW, rowY, resetW, controlH,
                GuiLang.raw("pvzce.editor.rule_reset_all", "全部恢复默认"), this::resetAllRules));

        ruleList.select(0);
        selectRule(ruleList.selected());
    }

    /** Applies the text field as the rule's value, clamped to the same range as the slider. */
    private void applyRuleBox() {
        if (currentRule == null || ruleSlider == null || ruleValueBox == null) {
            return;
        }
        GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(currentRule);
        if (type == null || type instanceof GameRuleType.BooleanRule) {
            return;
        }
        float parsed = GuiText.parseFloat(ruleValueBox.value(), ruleSlider.value());
        float clamped = Math.max(ruleSlider.min(), Math.min(ruleSlider.max(), parsed));
        ruleSlider.setValue(clamped);
        applyRuleSlider();
    }

    private void renderRuleRow(PvzceClient renderClient, Identifier id, int x, int y) {
        GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(id);
        int rowH = ruleList.entryHeight();
        boolean touched = rules.has(id.toString());
        renderClient.font().draw(GuiLang.name(id), x, y + rowH / 2F + 2F, 0.85F,
                touched ? 1F : 0.9F, touched ? 0.92F : 0.9F, touched ? 0.6F : 0.9F, 1F);
        String value = touched ? rules.get(id.toString()).toString()
                : type == null ? "" : String.valueOf(type.defaultValue());
        String suffix = touched ? "  (" + GuiLang.raw("pvzce.editor.rule_touched", "已改") + ")" : "";
        renderClient.font().draw(value + suffix, x + 12, y + rowH / 2F - 12F, 0.7F,
                touched ? 1F : 0.7F, touched ? 0.85F : 0.78F, touched ? 0.5F : 0.8F, 1F);
        renderClient.font().draw(id.toString(), x + 12, y + 3F, 0.58F, 0.55F, 0.58F, 0.6F, 1F);
    }

    private void selectRule(Identifier id) {
        currentRule = id;
        GameRuleType<?> type = id == null ? null : BuiltInRegistries.GAME_RULES.get(id);
        if (ruleSlider == null || ruleBoolButton == null) {
            return;
        }
        boolean numeric = type instanceof GameRuleType.IntRule || type instanceof GameRuleType.FloatRule
                || type instanceof GameRuleType.DoubleRule;
        boolean bool = type instanceof GameRuleType.BooleanRule;
        ruleSlider.setVisible(numeric);
        ruleValueBox.setVisible(numeric);
        ruleBoolButton.setVisible(bool);
        if (numeric) {
            float[] range = RULE_RANGES.getOrDefault(id.toString(), new float[]{0F, 1F});
            ruleSlider.configure(range[0], range[1], currentNumber(type), value -> {
            });
            ruleValueBox.setValue(formatRuleValue(currentNumber(type)), false);
        } else if (bool) {
            ruleBoolButton.setLabel(GuiLang.name(id) + "：" + (currentBoolean(type) ? "开" : "关"));
        }
    }

    private float currentNumber(GameRuleType<?> type) {
        JsonElement override = currentRule == null ? null : rules.get(currentRule.toString());
        Object value = override == null ? (type == null ? 0F : type.defaultValue()) : parseOverride(type, override);
        if (value instanceof Number number) {
            return number.floatValue();
        }
        return type != null && type.defaultValue() instanceof Number number ? number.floatValue() : 0F;
    }

    private boolean currentBoolean(GameRuleType<?> type) {
        JsonElement override = currentRule == null ? null : rules.get(currentRule.toString());
        Object value = override == null ? Boolean.FALSE : parseOverride(type, override);
        return value instanceof Boolean bool && bool;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object parseOverride(GameRuleType<?> type, JsonElement json) {
        if (type == null) {
            return null;
        }
        var result = ((GameRuleType) type).codec().parse(com.mojang.serialization.JsonOps.INSTANCE, json);
        return result.result().orElse(null);
    }

    private void applyRuleSlider() {
        if (currentRule == null || ruleSlider == null) {
            return;
        }
        GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(currentRule);
        if (type == null) {
            return;
        }
        float value = ruleSlider.value();
        if (INTEGER_RULES.contains(currentRule.toString())) {
            rules.addProperty(currentRule.toString(), Math.round(value));
        } else if (type instanceof GameRuleType.DoubleRule) {
            rules.addProperty(currentRule.toString(), (double) value);
        } else {
            rules.addProperty(currentRule.toString(), value);
        }
        if (ruleValueBox != null && !ruleValueBox.isFocused()) {
            ruleValueBox.setValue(formatRuleValue(value), false);
        }
    }

    /** Integers without a trailing ".0", floats to one decimal - matching the lang file. */
    private String formatRuleValue(float value) {
        return currentRule != null && INTEGER_RULES.contains(currentRule.toString())
                ? String.valueOf(Math.round(value))
                : GuiText.formatFloat(value);
    }

    private void toggleRuleBoolean() {
        if (currentRule == null) {
            return;
        }
        GameRuleType<?> type = BuiltInRegistries.GAME_RULES.get(currentRule);
        rules.addProperty(currentRule.toString(), !currentBoolean(type));
        selectRule(currentRule);
    }

    private void resetAllRules() {
        rules.entrySet().clear();
        selectRule(currentRule);
    }

    // ------------------------------------------------------------------
    // Wave / card / music / info pages
    // ------------------------------------------------------------------

    /**
     * The wave page: the whole schedule plus the selected wave's detail, on one screen.
     *
     * <p>This is the table and the composition editor that used to live in the separate
     * full-screen wave editor, moved into the page that owns it. The page used to be
     * three buttons and an empty panel, with the real editing one menu deeper - so the
     * page whose entire job is pacing told the author nothing and the work was somewhere
     * else.
     */
    private void buildWavePage() {
        int[] area = fullContentArea();
        int x = area[0];
        int y = area[1];
        int w = area[2];
        int h = area[3];
        int rowH = clamp(h / 16, 26, 34);
        int pad = 8;
        int gap = 10;

        // Left: the wave table. Right: the selected wave's parameters and composition.
        int tableW = (int) (w * 0.58F);
        int detailX = x + tableW + gap;
        int detailW = w - tableW - gap;

        int tableTop = y + h - rowH - pad - 20;
        int tableH = Math.max(60, tableTop - (y + rowH + pad + 20));
        waveList = own(new AbstractSelectionList<WaveEditorModel.WaveModel>(x, y + rowH + pad + 20,
                tableW, tableH, clamp(tableH / 8, 30, 44), (renderClient, wave, rx, ry) -> {
        }));
        waveList.setEntryRenderer(this::renderWaveRow);
        waveList.setEntries(new ArrayList<>(waveConfig.waves));

        // Wave list actions, under the table.
        int bw = Math.max(70, tableW / 6 - 6);
        int wy = y + pad;
        own(new Button(x, wy, bw, rowH, "新增波次", this::addWave));
        own(new Button(x + bw + 5, wy, bw, rowH, "删除波次", this::removeWave));
        own(new Button(x + (bw + 5) * 2, wy, bw, rowH, "上移", () -> moveWave(-1)));
        own(new Button(x + (bw + 5) * 3, wy, bw, rowH, "下移", () -> moveWave(1)));
        own(new Button(x + (bw + 5) * 4, wy, bw, rowH, "自动填充", this::fillDefaultWaves));
        own(new Button(x + (bw + 5) * 5, wy, Math.max(70, tableW - (bw + 5) * 5), rowH,
                "末波倍率 +", () -> adjustWaveMultiplier(0.05F)));
        own(new Button(x + (bw + 5) * 5, wy + rowH + 4, Math.max(70, tableW - (bw + 5) * 5), rowH,
                "末波倍率 -", () -> adjustWaveMultiplier(-0.05F)));

        // Detail: type / delay / warning, then composition.
        int fieldW = Math.max(60, (detailW - pad * 2 - gap) / 2);
        int top = y + h - rowH - pad;
        waveTypeButton = own(new Button(detailX, top, fieldW, rowH, "类型：小波", this::cycleWaveType));
        waveDelayBox = own(new EditBox(detailX + fieldW + gap, top, fieldW, rowH, this::commitWaveFields));
        waveWarningBox = own(new EditBox(detailX, top - rowH - 6, fieldW, rowH, this::commitWaveFields));
        waveCountBox = own(new EditBox(detailX + fieldW + gap, top - rowH - 6, fieldW, rowH,
                this::applyWaveCount));
        waveCountBox.setValueChangedListener(this::applyWaveCount);
        // The release interval belongs to the wave, so it sits with the wave's other
        // settings rather than with the level's.
        waveIntervalBox = own(new EditBox(detailX, top - (rowH + 6) * 2, fieldW, rowH,
                this::commitWaveFields));

        int listTop = top - (rowH + 6) * 3 - 20;
        int listBottom = y + rowH + pad + 22;
        int listH = Math.max(50, listTop - listBottom);
        int listW = Math.max(80, (detailW - pad * 2 - gap) / 2);
        waveEntryList = own(new PaletteList(detailX, listBottom, listW, listH,
                clamp(listH / 5, 26, 34), PaletteList.Kind.ENTITY));
        waveZombieList = own(new PaletteList(detailX + listW + gap, listBottom, listW, listH,
                clamp(listH / 5, 26, 34), PaletteList.Kind.ENTITY));
        waveZombieList.setItems(BuiltInRegistries.ZOMBIES.keySet().stream()
                .sorted()
                .map(id -> PaletteList.Item.of(client, PaletteList.Kind.ENTITY, id, null))
                .toList());

        int actionW = Math.max(64, (listW - 6) / 2);
        int ay = y + pad;
        own(new Button(detailX, ay, actionW, rowH, "加入组成", this::addWaveEntry));
        own(new Button(detailX + actionW + 6, ay, actionW, rowH, "移出组成", this::removeWaveEntry));

        refreshWaveDetail();
    }

    /** Column x offsets, shared with the full-screen wave editor's table. */
    private static final float[] WAVE_COLUMNS = {0.04F, 0.15F, 0.31F, 0.47F, 0.63F};

    private void renderWaveRow(PvzceClient renderClient, WaveEditorModel.WaveModel wave, int x, int y) {
        int index = waveConfig.waves.indexOf(wave);
        int rowH = waveList == null ? 30 : waveList.entryHeight();
        int tableW = waveList == null ? 200 : waveList.width();
        float[] colour = WaveEditorModel.typeColour(wave.type);
        renderClient.font().draw(String.valueOf(index + 1), x, y + rowH / 2F + 1F, 0.8F,
                colour[0], colour[1], colour[2], 1F);
        renderClient.font().draw(WaveEditorModel.typeName(wave.type), x + tableW * WAVE_COLUMNS[1],
                y + rowH / 2F + 1F, 0.78F, colour[0], colour[1], colour[2], 1F);
        renderClient.font().draw("间隔 " + wave.delay, x + tableW * WAVE_COLUMNS[2],
                y + rowH / 2F + 1F, 0.72F, 0.9F, 0.9F, 0.9F, 1F);
        renderClient.font().draw(wave.spawnInterval > 0 ? "出怪 " + wave.spawnInterval : "出怪 默认",
                x + tableW * WAVE_COLUMNS[3], y + rowH / 2F + 1F, 0.72F, 0.9F, 0.9F, 0.9F, 1F);
        renderClient.font().draw(wave.summary(), x + tableW * WAVE_COLUMNS[4],
                y + rowH / 2F + 3F, 0.7F, 0.95F, 0.95F, 0.95F, 1F);
        renderClient.font().draw("共 " + wave.total() + " 只", x + tableW * WAVE_COLUMNS[4],
                y + rowH / 2F - 11F, 0.62F, 0.7F, 0.78F, 0.8F, 1F);
    }

    private void adjustWaveMultiplier(float delta) {
        waveConfig.intervalEndMultiplier = Math.max(0.05F,
                Math.min(10F, waveConfig.intervalEndMultiplier + delta));
    }

    /** The wave the detail panel is editing. */
    /** The wave/cue the detail panel was last built for; selection-change detection. */
    private WaveEditorModel.WaveModel lastWaveShown;
    private MusicEditorModel.CueModel lastMusicCueShown;
    private String lastMusicEventChoice;

    private WaveEditorModel.WaveModel currentWave() {
        return waveList == null ? null : waveList.selected();
    }

    /** Pushes the selected wave's values into the detail widgets. */
    private void refreshWaveDetail() {
        WaveEditorModel.WaveModel wave = currentWave();
        boolean has = wave != null;
        if (waveTypeButton != null) {
            waveTypeButton.setActive(has);
            waveDelayBox.setActive(has);
            waveWarningBox.setActive(has);
            waveIntervalBox.setActive(has);
            waveTypeButton.setLabel(wave == null ? "类型：-" : "类型：" + WaveEditorModel.typeName(wave.type));
            // Only overwrite a field the author is not typing into.
            if (wave != null && !waveDelayBox.isFocused()) {
                waveDelayBox.setValue(String.valueOf(wave.delay), false);
            }
            if (wave != null && waveIntervalBox != null && !waveIntervalBox.isFocused()) {
                waveIntervalBox.setValue(wave.spawnInterval > 0
                        ? String.valueOf(wave.spawnInterval) : "", false);
            }
            if (wave != null && !waveWarningBox.isFocused()) {
                waveWarningBox.setValue(String.valueOf(wave.warningTicks), false);
            }
            if (wave == null) {
                waveDelayBox.setValue("", false);
                waveWarningBox.setValue("", false);
            }
        }
        if (waveEntryList != null) {
            waveEntryList.setItems(has
                    ? wave.entries.stream()
                            .map(entry -> PaletteList.Item.of(client, PaletteList.Kind.ENTITY,
                                    Identifier.tryParse(entry.id), "×" + entry.count))
                            .toList()
                    : List.of());
        }
        if (waveCountBox != null) {
            waveCountBox.setValue(currentWaveEntry() == null ? "1"
                    : String.valueOf(currentWaveEntry().count), false);
        }
    }

    private WaveEditorModel.EntryModel currentWaveEntry() {
        if (waveEntryList == null || currentWave() == null) {
            return null;
        }
        int index = waveEntryList.selectedIndex();
        return index >= 0 && index < currentWave().entries.size() ? currentWave().entries.get(index) : null;
    }

    private void commitWaveFields() {
        WaveEditorModel.WaveModel wave = currentWave();
        if (wave == null) {
            return;
        }
        wave.delay = GuiText.parseInt(waveDelayBox.value(), wave.delay, 1, 1_000_000);
        wave.warningTicks = GuiText.parseInt(waveWarningBox.value(), wave.warningTicks, 0, 1_000_000);
        // Empty stays empty: writing today's default into the file would freeze it.
        wave.spawnInterval = waveIntervalBox.value().isBlank()
                ? 0
                : GuiText.parseInt(waveIntervalBox.value(), wave.spawnInterval, 1, 100_000);
    }

    private void applyWaveCount() {
        WaveEditorModel.EntryModel entry = currentWaveEntry();
        if (entry == null) {
            return;
        }
        entry.count = GuiText.parseInt(waveCountBox.value(), entry.count, 1, 9999);
        int index = waveEntryList.selectedIndex();
        refreshWaveDetail();
        if (index >= 0) {
            waveEntryList.select(index);
        }
    }

    private void cycleWaveType() {
        WaveEditorModel.WaveModel wave = currentWave();
        if (wave == null) {
            return;
        }
        wave.type = switch (wave.type) {
            case "huge" -> "final";
            case "final" -> "small";
            default -> "huge";
        };
        int index = waveList.selectedIndex();
        waveList.setEntries(new ArrayList<>(waveConfig.waves));
        if (index >= 0) {
            waveList.select(index);
        }
        refreshWaveDetail();
    }

    private void addWave() {
        waveConfig.waves.add(new WaveEditorModel.WaveModel());
        waveList.setEntries(new ArrayList<>(waveConfig.waves));
        waveList.select(waveConfig.waves.size() - 1);
        refreshWaveDetail();
    }

    private void removeWave() {
        int index = waveList == null ? -1 : waveList.selectedIndex();
        if (index < 0 || index >= waveConfig.waves.size()) {
            if (!waveConfig.waves.isEmpty()) {
                waveConfig.waves.remove(waveConfig.waves.size() - 1);
            }
        } else {
            waveConfig.waves.remove(index);
        }
        waveList.setEntries(new ArrayList<>(waveConfig.waves));
        if (!waveConfig.waves.isEmpty()) {
            waveList.select(Math.min(Math.max(index, 0), waveConfig.waves.size() - 1));
        }
        refreshWaveDetail();
    }

    private void moveWave(int delta) {
        int index = waveList == null ? -1 : waveList.selectedIndex();
        int target = index + delta;
        if (index < 0 || target < 0 || target >= waveConfig.waves.size()) {
            return;
        }
        WaveEditorModel.WaveModel wave = waveConfig.waves.remove(index);
        waveConfig.waves.add(target, wave);
        waveList.setEntries(new ArrayList<>(waveConfig.waves));
        waveList.select(target);
        refreshWaveDetail();
    }

    /**
     * A starting wave list: four waves of one zombie each, which the author then edits.
     *
     * <p>An empty level has no waves at all and the game treats that as "no zombies", so
     * a new level is unplayable until the author builds a schedule by hand. The sizes
     * here mirror the demo level's shape rather than being arbitrary.
     */
    private void fillDefaultWaves() {
        if (!waveConfig.waves.isEmpty()) {
            return;
        }
        String[][] plan = {{"small", "600", "1"}, {"small", "1200", "2"}, {"huge", "1200", "3"},
                {"final", "1200", "3"}};
        for (String[] spec : plan) {
            WaveEditorModel.WaveModel wave = new WaveEditorModel.WaveModel();
            wave.type = spec[0];
            wave.delay = Integer.parseInt(spec[1]);
            wave.warningTicks = "small".equals(spec[0]) ? 0 : 600;
            wave.entries.add(new WaveEditorModel.EntryModel("pvzce:basic_zombie",
                    Integer.parseInt(spec[2])));
            waveConfig.waves.add(wave);
        }
        waveList.setEntries(new ArrayList<>(waveConfig.waves));
        waveList.select(0);
        refreshWaveDetail();
    }

    private void addWaveEntry() {
        WaveEditorModel.WaveModel wave = currentWave();
        if (wave == null || waveZombieList == null) {
            return;
        }
        Identifier id = waveZombieList.selectedId();
        if (id == null) {
            return;
        }
        int amount = GuiText.parseInt(waveCountBox == null ? "1" : waveCountBox.value(), 1, 1, 9999);
        WaveEditorModel.EntryModel target = null;
        for (WaveEditorModel.EntryModel entry : wave.entries) {
            if (entry.id.equals(id.toString())) {
                entry.count += amount;
                target = entry;
                break;
            }
        }
        if (target == null) {
            target = new WaveEditorModel.EntryModel(id.toString(), amount);
            wave.entries.add(target);
        }
        refreshWaveDetail();
        int index = wave.entries.indexOf(target);
        if (index >= 0) {
            waveEntryList.select(index);
        }
    }

    private void removeWaveEntry() {
        WaveEditorModel.WaveModel wave = currentWave();
        WaveEditorModel.EntryModel entry = currentWaveEntry();
        if (wave == null || entry == null) {
            return;
        }
        int removed = wave.entries.indexOf(entry);
        wave.entries.remove(entry);
        refreshWaveDetail();
        if (!wave.entries.isEmpty()) {
            waveEntryList.select(Math.min(Math.max(removed, 0), wave.entries.size() - 1));
            waveEntryList.selected();
        }
    }

    /**
     * The card page: the level's own cards, and every card the game can offer.
     *
     * <p>Two lists, and no per-card "fixed" flag to think about. The rule is positional: the
     * cards listed here are the level's and are pinned, and whatever is left of the slot
     * count is the player's to fill from the right-hand list. A page of fixed/optional
     * toggles was more machinery than the rule needs.
     */
    private void buildCardPage() {
        int rowH = clamp(centerH / 14, 26, 34);
        int actionRows = 2;
        int actionBlock = rowH * actionRows + 18;
        cardListTop = centerY + actionBlock;
        int listH = Math.max(90, centerH - actionBlock - 24);
        int gap = 10;
        int colW = Math.max(90, (centerW + sideW + 10 - gap) / 2);
        int availX = centerX + colW + gap;

        poolList = own(new AbstractSelectionList<String>(centerX, cardListTop, colW, listH,
                clamp(listH / 8, 26, 38), (renderClient, id, rx, ry) -> {
        }));
        poolList.setEntryRenderer((renderClient, id, rx, ry) -> renderCardRow(renderClient, id, rx, ry, false));
        poolList.setEntries(new ArrayList<>(cardPoolConfig.pool));

        rebuildAvailableRows();
        availableList = own(new AbstractSelectionList<AvailableRow>(availX, cardListTop, colW, listH,
                clamp(listH / 8, 26, 38), (renderClient, row, rx, ry) -> {
        }));
        availableList.setEntryRenderer(this::renderAvailableRow);
        availableList.setEntries(new ArrayList<>(availableRows));

        int actionW = Math.max(72, (colW - gap) / 2);
        int y = centerY + 8;
        own(new Button(centerX, y, actionW, rowH, "加入卡池", this::addPoolEntry));
        own(new Button(centerX + actionW + gap, y, actionW, rowH, "移出卡池", this::removePoolEntry));
        own(new Button(availX, y, actionW, rowH, "全部加入", this::addAllPool));
        own(new Button(availX + actionW + gap, y, actionW, rowH, "恢复默认卡", this::keepOnlyDefaults));
        own(new Button(centerX, y + rowH + 4, actionW, rowH, "卡池上移", () -> movePoolEntry(-1)));
        own(new Button(centerX + actionW + gap, y + rowH + 4, actionW, rowH, "卡池下移", () -> movePoolEntry(1)));
        own(new Button(availX, y + rowH + 4, actionW, rowH, "卡槽上限 +", () -> adjustMaxSeedSlots(1)));
        own(new Button(availX + actionW + gap, y + rowH + 4, actionW, rowH, "卡槽上限 -",
                () -> adjustMaxSeedSlots(-1)));
    }

    /** A row in the available list: a section header or a card. */
    private record AvailableRow(String header, String cardId) {
        static AvailableRow header(String text) {
            return new AvailableRow(text, null);
        }

        static AvailableRow card(String id) {
            return new AvailableRow(null, id);
        }

        boolean isHeader() {
            return cardId == null;
        }
    }

    private final List<AvailableRow> availableRows = new ArrayList<>();

    /** Regroups the card list by kind, so plants, resources and tools read as blocks. */
    private void rebuildAvailableRows() {
        availableRows.clear();
        for (String kind : List.of("plant", "resource", "tool", "other")) {
            List<String> ids = cardPoolConfig.available.stream()
                    .filter(id -> kind.equals(kindOf(id)))
                    .sorted()
                    .toList();
            if (ids.isEmpty()) {
                continue;
            }
            availableRows.add(AvailableRow.header(kindLabel(kind) + "（" + ids.size() + "）"));
            for (String id : ids) {
                availableRows.add(AvailableRow.card(id));
            }
        }
    }

    /** A slot's kind from its resolved card, defaulting to "other". */
    private static String kindOf(String slotId) {
        Identifier id = Identifier.tryParse(slotId);
        if (id == null) {
            return "other";
        }
        return com.pvzce.common.core.SlotResolver.resolve(id)
                .map(card -> card.kind().json())
                .orElse("other");
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "plant" -> "植物";
            case "resource" -> "资源";
            case "tool" -> "工具";
            default -> "其它";
        };
    }

    /** The card ids currently in the list, headers skipped. */
    private List<String> availableIds() {
        return availableRows.stream().filter(row -> !row.isHeader()).map(AvailableRow::cardId).toList();
    }

    private String selectedAvailableId() {
        if (availableList == null) {
            return null;
        }
        AvailableRow row = availableList.selected();
        return row == null || row.isHeader() ? null : row.cardId();
    }

    private void renderAvailableRow(PvzceClient renderClient, AvailableRow row, int x, int y) {
        int rowH = availableList.entryHeight();
        if (row.isHeader()) {
            SpriteRenderer.solid(x - 6, y, Math.max(10, availableList.width() - 10), rowH, 0.05F,
                    0.22F, 0.28F, 0.34F, 0.9F);
            renderClient.font().draw(row.header(), x - 2, y + rowH / 2F + 1F, 0.78F, 1F, 0.95F, 0.75F, 1F);
            return;
        }
        renderCardRow(renderClient, row.cardId(), x, y, true);
    }

    /** One card row: icon, name, short id, and whether the level already uses it. */
    private void renderCardRow(PvzceClient renderClient, String id, int x, int y, boolean inAvailable) {
        Identifier parsed = Identifier.tryParse(id);
        AbstractSelectionList<?> list = inAvailable ? availableList : poolList;
        int rowH = list == null ? 30 : list.entryHeight();
        Identifier icon = PaletteList.iconFor(client, PaletteList.Kind.ENTITY, parsed);
        int iconSize = Math.max(18, (int) (rowH * 0.7F));
        if (icon != null) {
            renderClient.drawTexture(icon, x, y + (rowH - iconSize) / 2F, iconSize, iconSize,
                    0.1F, 1F, 1F, 1F, 1F);
        }
        float textX = x + iconSize + 6;
        renderClient.font().draw(GuiLang.name(parsed), textX, y + rowH / 2F + 1F, 0.78F, 1F, 1F, 1F, 1F);
        renderClient.font().draw(GuiText.shortId(id), textX, y + rowH / 2F - 12F, 0.6F, 0.6F, 0.65F, 0.7F, 1F);
        if (inAvailable && cardPoolConfig.pool.contains(id)) {
            String marker = "已选";
            float scale = 0.6F;
            float markerW = renderClient.font().width(marker, scale);
            renderClient.font().draw(marker, x + Math.max(0F, list.width() - markerW - 10F),
                    y + rowH - 12F, scale, 0.95F, 0.85F, 0.5F, 1F);
        }
    }

    private void addPoolEntry() {
        String id = selectedAvailableId();
        if (id == null || cardPoolConfig.pool.contains(id)) {
            return;
        }
        cardPoolConfig.pool.add(id);
        refreshCardLists();
    }

    private void addAllPool() {
        Set<String> existing = new LinkedHashSet<>(cardPoolConfig.pool);
        for (String id : availableIds()) {
            if (existing.add(id)) {
                cardPoolConfig.pool.add(id);
            }
        }
        refreshCardLists();
    }

    /** Resets the bar to the level's default cards, which is what a new level starts from. */
    private void keepOnlyDefaults() {
        cardPoolConfig.pool.clear();
        for (String slot : defaultLevelCards()) {
            if (cardPoolConfig.available.contains(slot)) {
                cardPoolConfig.pool.add(slot);
            }
        }
        cardPoolConfig.maxSeedSlots = Math.max(cardPoolConfig.pool.size(), cardPoolConfig.maxSeedSlots);
        maxSeedSlots = cardPoolConfig.maxSeedSlots;
        refreshCardLists();
    }

    private void removePoolEntry() {
        int index = poolList == null ? -1 : poolList.selectedIndex();
        if (index < 0 || index >= cardPoolConfig.pool.size()) {
            return;
        }
        cardPoolConfig.pool.remove(index);
        refreshCardLists();
    }

    private void movePoolEntry(int delta) {
        int index = poolList == null ? -1 : poolList.selectedIndex();
        int target = index + delta;
        if (index < 0 || target < 0 || target >= cardPoolConfig.pool.size()) {
            return;
        }
        String value = cardPoolConfig.pool.remove(index);
        cardPoolConfig.pool.add(target, value);
        refreshCardLists();
        poolList.select(target);
    }

    /**
     * Adjusts the slot count.
     *
     * <p>Floored at the number of cards the level lists: the level's own cards cannot be
     * dropped by shrinking the bar, because {@code LevelDef} raises the count back to fit
     * them when the level loads.
     */
    private void adjustMaxSeedSlots(int delta) {
        int floor = Math.min(cardPoolConfig.pool.size(), CardPoolEditorDialog.MAX_SEED_SLOTS_LIMIT);
        cardPoolConfig.maxSeedSlots = clamp(cardPoolConfig.maxSeedSlots + delta, floor,
                CardPoolEditorDialog.MAX_SEED_SLOTS_LIMIT);
        maxSeedSlots = cardPoolConfig.maxSeedSlots;
    }

    private void refreshCardLists() {
        if (poolList != null) {
            int keep = poolList.selectedIndex();
            poolList.setEntries(new ArrayList<>(cardPoolConfig.pool));
            if (keep >= 0 && keep < cardPoolConfig.pool.size()) {
                poolList.select(keep);
            }
        }
        if (availableList != null) {
            int keep = availableList.selectedIndex();
            rebuildAvailableRows();
            availableList.setEntries(new ArrayList<>(availableRows));
            if (keep >= 0 && keep < availableRows.size()) {
                availableList.select(keep);
            }
        }
    }
    /**
     * The music page: the cue timeline and the selected cue's parameters, on one screen.
     *
     * <p>This is the dialog's content moved into the page that owns it. The dialog was a
     * second-level menu reachable only from a button on this page (and from a duplicate
     * button in the level action bar), and a level's music is a property of the level, not
     * a modal detour from it.
     */
    private void buildMusicPage() {
        int[] area = fullContentArea();
        int x = area[0];
        int y = area[1];
        int w = area[2];
        int h = area[3];
        int rowH = clamp(h / 16, 26, 34);
        int pad = 8;
        int gap = 10;

        int listW = (int) (w * 0.52F);
        int detailX = x + listW + gap;
        int detailW = w - listW - gap;

        int listTop = y + h - rowH - pad - 20;
        int listH = Math.max(60, listTop - (y + rowH + pad + 20));
        musicCueList = own(new AbstractSelectionList<MusicEditorModel.CueModel>(x, y + rowH + pad + 20,
                listW, listH, clamp(listH / 8, 28, 40), (renderClient, cue, rx, ry) -> {
        }));
        musicCueList.setEntryRenderer(this::renderMusicCueRow);
        musicCueList.setEntries(new ArrayList<>(musicConfig.cues));

        int bw = Math.max(70, listW / 5 - 6);
        int by = y + pad;
        own(new Button(x, by, bw, rowH, "新增提示音", this::addMusicCue));
        own(new Button(x + bw + 5, by, bw, rowH, "删除", this::removeMusicCue));
        own(new Button(x + (bw + 5) * 2, by, bw, rowH, "上移", () -> moveMusicCue(-1)));
        own(new Button(x + (bw + 5) * 3, by, bw, rowH, "下移", () -> moveMusicCue(1)));
        own(new Button(x + (bw + 5) * 4, by, Math.max(70, listW - (bw + 5) * 4), rowH,
                "清空时间线", () -> {
                    musicConfig.cues.clear();
                    refreshMusicCueList();
                    refreshMusicDetail();
                }));

        int fieldW = Math.max(56, (detailW - pad * 2 - gap) / 3);
        int top = y + h - rowH - pad;
        musicTickBox = own(new EditBox(detailX, top, fieldW, rowH, this::commitMusicFields));
        musicVolumeBox = own(new EditBox(detailX + fieldW + gap, top, fieldW, rowH, this::commitMusicFields));
        musicFadeBox = own(new EditBox(detailX + (fieldW + gap) * 2, top, fieldW, rowH,
                this::commitMusicFields));
        musicTrackButton = own(new Button(detailX, top - rowH - 6, fieldW * 2 + gap, rowH,
                "轨道：background", this::cycleMusicTrack));
        musicLoopButton = own(new Button(detailX + (fieldW * 2 + gap) + gap, top - rowH - 6,
                fieldW, rowH, "循环：开", this::toggleMusicLoop));
        musicStopButton = own(new Button(detailX, top - (rowH + 6) * 2, fieldW * 2 + gap, rowH,
                "停止：关", this::toggleMusicStop));

        int eventsTop = top - (rowH + 6) * 2 - 20;
        int eventsBottom = y + pad + rowH + 6;
        musicEventList = own(new AbstractSelectionList<String>(detailX, eventsBottom, detailW,
                Math.max(50, eventsTop - eventsBottom), clamp(rowH - 2, 24, 32),
                (renderClient, event, rx, ry) -> renderClient.font().draw(event, rx, ry + 4,
                        0.68F, 0.9F, 0.95F, 0.9F, 1F)));
        musicEventList.setEntries(musicEventOptions());

        refreshMusicDetail();
    }

    /**
     * The unlock page: what a level asks for before it can be played.
     *
     * <p>Two-column, like the wave and music pages: the editable list on the left, and on
     * the right the things that can be added to it plus the purchase price. Prerequisite
     * levels are picked from the registry rather than typed from memory - a level id is a
     * path like {@code pvzce:yard/adventure/1_1}, and a typo locks the level forever with
     * no clue why.
     */
    private void buildUnlockPage() {
        int[] area = fullContentArea();
        int x = area[0];
        int y = area[1];
        int w = area[2];
        int h = area[3];
        int pad = 10;
        // Three rows in two columns, laid out bottom-up from the button row. The band is
        // about 415 logical pixels tall at 720p with the automatic GUI scale of 2, so the
        // row height comes from what is left after the button row and two label gaps -
        // deriving it from ``h / n`` instead ran the top row out of the panel and behind
        // the navigation bar.
        int gap = 16;
        int labelGap = 20;
        int colW = Math.max(150, (w - gap) / 2);
        int rightX = x + colW + gap;
        int fieldW = colW - pad * 2;

        // Four bands stacked from the bottom: the button row, then the card row, then the
        // level row, each with room above it for its own label. The row height comes from
        // what is left, capped so a tall window does not produce absurdly deep text boxes.
        int rowH = clamp((h - pad * 2 - 96) / 3, 24, 40);
        int buttonY = y + pad;
        int cardsY = buttonY + rowH + 38;
        int levelsY = cardsY + rowH + 38;

        unlockLevelsBox = own(new EditBox(x, levelsY, fieldW, rowH, this::commitUnlockFields));
        unlockLevelsBox.setValue(unlockLevels, false);
        unlockCardsBox = own(new EditBox(x, cardsY, fieldW, rowH, this::commitUnlockFields));
        unlockCardsBox.setValue(unlockCards, false);
        unlockCostBox = own(new EditBox(rightX, levelsY, Math.max(80, fieldW / 3), rowH,
                this::commitUnlockFields));
        unlockCostBox.setValue(String.valueOf(unlockCost), false);
        unlockHiddenButton = own(new Button(rightX, cardsY, fieldW, rowH,
                unlockHidden ? "隐藏关：开" : "隐藏关：关", this::toggleUnlockHidden));

        int bw = Math.max(80, (w - 20) / 3);
        own(new Button(x, buttonY, bw, rowH, "移除最后的前置关卡", () -> removeFromUnlockBox(true)));
        own(new Button(x + bw + 10, buttonY, bw, rowH, "移除最后的所需卡", () -> removeFromUnlockBox(false)));
        own(new Button(x + (bw + 10) * 2, buttonY, bw, rowH, "清空条件", () -> {
            unlockLevels = "";
            unlockCards = "";
            unlockLevelsBox.setValue("", false);
            unlockCardsBox.setValue("", false);
            commitUnlockFields();
        }));
    }

    /**
     * The cards a level may require: plants and tools, never resources.
     *
     * <p>A resource card is not part of the backpack ({@code SlotResolver.requiresUnlock}
     * returns false for it), so requiring one would be a condition that is always already
     * true - a trap rather than a gate.
     */
    private static List<String> unlockableCardIds() {
        List<String> cards = new ArrayList<>();
        BuiltInRegistries.SLOT_TYPES.keySet().stream()
                .filter(id -> {
                    var slot = BuiltInRegistries.SLOT_TYPES.get(id);
                    return slot != null && com.pvzce.common.core.SlotResolver.requiresUnlock(slot.id());
                })
                .map(Identifier::toString)
                .sorted()
                .forEach(cards::add);
        return cards;
    }

    /**
     * Takes the last id back out of one of the boxes.
     *
     * <p>"Remove the last one" rather than "remove the selected one": the text box is the
     * source of truth, the list is only a picker, and a mis-click that appended the wrong
     * level should be one click to undo.
     */
    private void removeFromUnlockBox(boolean levels) {
        List<String> ids = splitIdList(levels ? unlockLevels : unlockCards);
        if (ids.isEmpty()) {
            return;
        }
        ids.remove(ids.size() - 1);
        String joined = String.join(", ", ids);
        if (levels) {
            unlockLevels = joined;
            unlockLevelsBox.setValue(joined, false);
        } else {
            unlockCards = joined;
            unlockCardsBox.setValue(joined, false);
        }
    }

    private void toggleUnlockHidden() {
        unlockHidden = !unlockHidden;
        if (unlockHiddenButton != null) {
            unlockHiddenButton.setLabel(unlockHidden ? "隐藏关：开" : "隐藏关：关");
        }
    }

    /**
     * Pulls the two id boxes into the fields.
     *
     * <p>An unknown id is reported and dropped by the {@code LevelValidator} on load, so
     * the editor keeps what was typed: refusing to save a level because a prerequisite is
     * misspelled would hide the typo behind a dialog instead of a log line.
     */
    private void commitUnlockFields() {
        if (unlockLevelsBox != null) {
            unlockLevels = unlockLevelsBox.value();
        }
        if (unlockCardsBox != null) {
            unlockCards = unlockCardsBox.value();
        }
        if (unlockCostBox != null) {
            unlockCost = GuiText.parseInt(unlockCostBox.value(), unlockCost, 0, 100_000);
        }
    }

    /**
     * The unlock page's labels, its explanation, and the live validation of what was typed.
     *
     * <p>The validation runs the same checks the server's {@code LevelValidator} does on
     * load, so a misspelled prerequisite shows up while typing instead of as a level that
     * can never be entered.
     */
    private void renderUnlockPage() {
        if (unlockLevelsBox == null) {
            return;
        }
        float labelScale = 0.78F;
        client.font().draw("前置关卡（需先通关；逗号分隔，留空表示不限）",
                unlockLevelsBox.x(), unlockLevelsBox.y() + unlockLevelsBox.height() + 5F,
                labelScale, 0.9F, 0.9F, 0.9F, 1F);
        client.font().draw("所需卡（需已解锁；同上）",
                unlockCardsBox.x(), unlockCardsBox.y() + unlockCardsBox.height() + 5F,
                labelScale, 0.9F, 0.9F, 0.9F, 1F);
        client.font().draw("金币解锁价（0 = 不可购买）",
                unlockCostBox.x(), unlockCostBox.y() + unlockCostBox.height() + 5F,
                labelScale, 0.9F, 0.9F, 0.9F, 1F);
        client.font().draw("隐藏关（未满足条件时不出现在列表里）",
                unlockHiddenButton.x(), unlockHiddenButton.y() + unlockHiddenButton.height() + 5F,
                labelScale, 0.9F, 0.9F, 0.9F, 1F);

        List<String> problems = new ArrayList<>();
        for (String id : splitIdList(unlockLevels)) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed == null || BuiltInRegistries.LEVELS.get(parsed) == null) {
                problems.add("未知关卡 " + id);
            } else if (parsed.equals(levelId)) {
                problems.add("本关不能作为自己的前置");
            }
        }
        for (String id : splitIdList(unlockCards)) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed == null || com.pvzce.common.core.SlotResolver.resolve(parsed).isEmpty()) {
                problems.add("未知卡 " + id);
            }
        }

        // One status line, in the gap between the price row and the card row: the only
        // horizontal space on this page not already spoken for by a label.
        List<String> picked = new ArrayList<>(splitIdList(unlockLevels));
        picked.addAll(splitIdList(unlockCards));
        float statusY = (unlockCostBox.y() + unlockCostBox.height() + unlockCardsBox.y()) / 2F;
        if (!problems.isEmpty()) {
            drawTrimmed("有问题：" + String.join("；", problems), rightX(), statusY, 0.72F,
                    1F, 0.5F, 0.4F);
        } else if (picked.isEmpty() && unlockCost <= 0 && !unlockHidden) {
            client.font().draw("本关无条件：任何玩家都可以直接进入", rightX(), statusY, 0.72F,
                    0.7F, 0.9F, 0.7F, 1F);
        } else {
            String note = "条件生效：需解锁 " + picked.size() + " 项"
                    + (unlockCost > 0 ? "，或花 " + unlockCost + " 金币买下" : "")
                    + (unlockHidden ? "；未满足前不出现在列表里" : "");
            client.font().draw(note, rightX(), statusY, 0.72F, 0.85F, 0.9F, 0.6F, 1F);
        }
    }

    /** The x where the right column starts, read back from one of its widgets. */
    private float rightX() {
        return unlockCostBox == null ? 0F : unlockCostBox.x();
    }

    /**
     * Draws a message, shortening it until it fits the half-panel it lives in.
     *
     * <p>A prerequisite id is long; a list of them is longer than half a 1280px window,
     * and text drawn past the panel runs over the board instead of being clipped.
     */
    private void drawTrimmed(String text, float x, float y, float scale, float r, float g, float b) {
        String shown = text;
        float limit = Math.max(120F, client.guiWidth() / 2F - x - 20F);
        while (shown.length() > 12 && client.font().width(shown, scale) > limit) {
            shown = shown.substring(0, shown.length() - 4);
        }
        if (!shown.equals(text)) {
            shown = shown + "…";
        }
        client.font().draw(shown, x, y, scale, r, g, b, 1F);
    }

    /**
     * The events a cue may name: stop, plus every registered {@code music/} sound.
     *
     * <p>Read from the sound-event registry rather than a literal list, so a data pack or a
     * mod that registers a music event gets it in this list.
     */
    private static List<String> musicEventOptions() {
        List<String> events = new ArrayList<>();
        events.add(MusicEditorModel.STOP_LABEL);
        BuiltInRegistries.SOUND_EVENTS.keySet().stream()
                .filter(id -> id.path().startsWith("music/"))
                .map(Identifier::toString)
                .sorted()
                .forEach(events::add);
        return events;
    }

    private void renderMusicCueRow(PvzceClient renderClient, MusicEditorModel.CueModel cue, int x, int y) {
        int rowH = musicCueList == null ? 30 : musicCueList.entryHeight();
        String event = cue.event == null || cue.event.isEmpty() ? MusicEditorModel.STOP_LABEL : cue.event;
        String head = "tick " + cue.atTick + "　" + cue.track;
        renderClient.font().draw(head, x, y + rowH / 2F + 1F, 0.76F, 1F, 1F, 1F, 1F);
        renderClient.font().draw(GuiText.shortId(event), x, y + rowH / 2F - 12F, 0.66F,
                cue.stop ? 0.95F : 0.8F, cue.stop ? 0.7F : 0.85F, 0.7F, 1F);
    }

    private MusicEditorModel.CueModel currentMusicCue() {
        return musicCueList == null ? null : musicCueList.selected();
    }

    private void refreshMusicDetail() {
        MusicEditorModel.CueModel cue = currentMusicCue();
        boolean has = cue != null;
        if (musicTickBox == null) {
            return;
        }
        musicTickBox.setActive(has);
        musicVolumeBox.setActive(has);
        musicFadeBox.setActive(has);
        musicTrackButton.setActive(has);
        musicLoopButton.setActive(has);
        musicStopButton.setActive(has);
        if (!has) {
            musicTickBox.setValue("", false);
            musicVolumeBox.setValue("", false);
            musicFadeBox.setValue("", false);
            musicTrackButton.setLabel("轨道：-");
            return;
        }
        if (!musicTickBox.isFocused()) {
            musicTickBox.setValue(String.valueOf(cue.atTick), false);
        }
        if (!musicVolumeBox.isFocused()) {
            musicVolumeBox.setValue(GuiText.formatFloat(cue.volume), false);
        }
        if (!musicFadeBox.isFocused()) {
            musicFadeBox.setValue(GuiText.formatFloat(cue.fadeSeconds), false);
        }
        musicTrackButton.setLabel("轨道：" + cue.track);
        musicLoopButton.setLabel(cue.loop ? "循环：开" : "循环：关");
        musicStopButton.setLabel(cue.stop ? "停止：开" : "停止：关");
    }

    private void commitMusicFields() {
        MusicEditorModel.CueModel cue = currentMusicCue();
        if (cue == null) {
            return;
        }
        cue.atTick = GuiText.parseInt(musicTickBox.value(), cue.atTick, 0, 10_000_000);
        cue.volume = GuiText.parseFloat(musicVolumeBox.value(), cue.volume, 0F, 4F);
        cue.fadeSeconds = GuiText.parseFloat(musicFadeBox.value(), cue.fadeSeconds, 0F, 60F);
    }

    private void cycleMusicTrack() {
        MusicEditorModel.CueModel cue = currentMusicCue();
        if (cue == null) {
            return;
        }
        cue.track = switch (cue.track) {
            case "menu" -> "battle";
            case "battle" -> "stinger";
            case "stinger" -> "background";
            default -> "menu";
        };
        refreshMusicDetail();
    }

    private void toggleMusicLoop() {
        MusicEditorModel.CueModel cue = currentMusicCue();
        if (cue == null) {
            return;
        }
        cue.loop = !cue.loop;
        refreshMusicDetail();
    }

    private void toggleMusicStop() {
        MusicEditorModel.CueModel cue = currentMusicCue();
        if (cue == null) {
            return;
        }
        cue.stop = !cue.stop;
        if (cue.stop) {
            cue.event = "";
        }
        refreshMusicDetail();
        refreshMusicCueList();
    }

    private void addMusicCue() {
        MusicEditorModel.CueModel cue = new MusicEditorModel.CueModel();
        cue.event = "pvzce:music/grasswalk";
        musicConfig.cues.add(cue);
        refreshMusicCueList();
        musicCueList.select(musicConfig.cues.size() - 1);
        refreshMusicDetail();
    }

    private void removeMusicCue() {
        int index = musicCueList == null ? -1 : musicCueList.selectedIndex();
        if (index < 0 || index >= musicConfig.cues.size()) {
            return;
        }
        musicConfig.cues.remove(index);
        refreshMusicCueList();
        if (!musicConfig.cues.isEmpty()) {
            musicCueList.select(Math.min(index, musicConfig.cues.size() - 1));
        }
        refreshMusicDetail();
    }

    private void moveMusicCue(int delta) {
        int index = musicCueList == null ? -1 : musicCueList.selectedIndex();
        int target = index + delta;
        if (index < 0 || target < 0 || target >= musicConfig.cues.size()) {
            return;
        }
        MusicEditorModel.CueModel cue = musicConfig.cues.remove(index);
        musicConfig.cues.add(target, cue);
        refreshMusicCueList();
        musicCueList.select(target);
        refreshMusicDetail();
    }

    private void refreshMusicCueList() {
        if (musicCueList != null) {
            int keep = musicCueList.selectedIndex();
            musicCueList.setEntries(new ArrayList<>(musicConfig.cues));
            if (keep >= 0 && keep < musicConfig.cues.size()) {
                musicCueList.select(keep);
            }
        }
    }

    /** Vertical pitch between the info page's fields; the labels live in the gap. */
    private int infoRowPitch;

    private void buildInfoPage() {
        groupThemeButtons.clear();
        groupCategoryButtons.clear();
        int rowH = clamp(centerH / 14, 26, 34);
        infoRowPitch = rowH + 30;
        int fieldW = Math.min(460, centerW + sideW + 10);
        int top = centerY + centerH - rowH;
        nameBox = own(new EditBox(centerX, top, fieldW, rowH, () -> {
        }));
        nameBox.setValue(levelName, false);
        descriptionBox = own(new EditBox(centerX, top - infoRowPitch, fieldW, rowH, () -> {
        }));
        descriptionBox.setValue(description, false);
        sunBox = own(new EditBox(centerX, top - infoRowPitch * 2, Math.min(160, fieldW / 3), rowH, () -> {
        }));
        sunBox.setValue(String.valueOf(initialSun), false);

        // Rewards sit under the sun field: they are the other "what does this level
        // give the player" numbers, and keeping them on the same page means a level's
        // whole economy is in one place.
        int halfW = Math.max(80, fieldW / 2 - 6);
        rewardUnlockBox = own(new EditBox(centerX, top - infoRowPitch * 3, fieldW, rowH, () -> {
        }));
        rewardUnlockBox.setValue(rewardUnlock, false);
        repeatCoinsBox = own(new EditBox(centerX, top - infoRowPitch * 4, halfW, rowH, () -> {
        }));
        repeatCoinsBox.setValue(String.valueOf(rewardRepeatCoins), false);
        coinDropChanceBox = own(new EditBox(centerX + halfW + 12, top - infoRowPitch * 4, halfW, rowH, () -> {
        }));
        coinDropChanceBox.setValue(GuiText.formatFloat(rewardCoinDropChance), false);
        coinDropAmountBox = own(new EditBox(centerX, top - infoRowPitch * 5, halfW, rowH, () -> {
        }));
        coinDropAmountBox.setValue(String.valueOf(rewardCoinDropAmount), false);
        // Which coin a zombie leaves: the four denominations are separate resources
        // (10 / 50 / 1000 / 250), so the level picks one by id.
        coinDropBox = own(new EditBox(centerX + halfW + 12, top - infoRowPitch * 5, halfW, rowH, () -> {
        }));
        coinDropBox.setValue(rewardCoinDrop, false);

        groupRowWidth = fieldW;
        groupRowHeight = rowH;
        groupThemeY = top - infoRowPitch * 6;
        groupCategoryY = groupThemeY - rowH - 14;
        LevelGrouping.Group group = LevelGrouping.resolve(levelId, themeIds(), categoryIds());
        selectedThemeId = group.theme();
        selectedCategoryId = group.category();
        buildGroupRows();
    }

    // ------------------------------------------------------------------
    // Theme / category (the level's page)
    // ------------------------------------------------------------------

    /** The theme/category choice buttons on the info page. */
    private final List<Button> groupThemeButtons = new ArrayList<>();
    private final List<Button> groupCategoryButtons = new ArrayList<>();
    private Identifier selectedThemeId = LevelGrouping.UNCATEGORIZED;
    private Identifier selectedCategoryId = LevelGrouping.UNCATEGORIZED;
    private int groupThemeY;
    private int groupCategoryY;
    private int groupRowWidth;
    private int groupRowHeight;

    private static final String UNCATEGORIZED_KEY = "pvzce.level_uncategorized";
    private static final String UNCATEGORIZED_TEXT = "未分类";

    /** Every theme the server's tab table offers, plus the unclassified bucket. */
    private List<Identifier> themeIds() {
        java.util.LinkedHashSet<Identifier> ids = new java.util.LinkedHashSet<>();
        for (var tab : client.levelTabs()) {
            Identifier id = Identifier.tryParse(tab.theme());
            if (!LevelGrouping.isUncategorized(id)) {
                ids.add(id);
            }
        }
        ids.add(LevelGrouping.UNCATEGORIZED);
        return List.copyOf(ids);
    }

    /** The categories the open theme offers, plus the unclassified bucket. */
    private List<Identifier> categoryIds() {
        java.util.LinkedHashSet<Identifier> ids = new java.util.LinkedHashSet<>();
        for (var tab : client.levelTabs()) {
            if (selectedThemeId != null && selectedThemeId.toString().equals(tab.theme())) {
                Identifier id = Identifier.tryParse(tab.category());
                if (!LevelGrouping.isUncategorized(id)) {
                    ids.add(id);
                }
            }
        }
        ids.add(LevelGrouping.UNCATEGORIZED);
        return List.copyOf(ids);
    }

    private void buildGroupRows() {
        buildGroupRow(groupThemeButtons, themeIds(), groupThemeY, id -> {
            if (id.equals(selectedThemeId)) {
                return;
            }
            selectedThemeId = id;
            selectedCategoryId = LevelGrouping.UNCATEGORIZED;
            List<Identifier> categories = categoryIds();
            if (!categories.isEmpty()) {
                selectedCategoryId = categories.get(0);
            }
            rebuildGroupRows();
        });
        buildGroupRow(groupCategoryButtons, categoryIds(), groupCategoryY, id -> {
            if (!id.equals(selectedCategoryId)) {
                selectedCategoryId = id;
                refreshGroupState();
            }
        });
        refreshGroupState();
    }

    /** Rebuilds both rows, which is what a theme change needs (its categories changed). */
    private void rebuildGroupRows() {
        for (Button button : groupThemeButtons) {
            widgets.remove(button);
            pageWidgets.remove(button);
        }
        for (Button button : groupCategoryButtons) {
            widgets.remove(button);
            pageWidgets.remove(button);
        }
        groupThemeButtons.clear();
        groupCategoryButtons.clear();
        buildGroupRows();
    }

    private void buildGroupRow(List<Button> target, List<Identifier> ids, int y,
                               java.util.function.Consumer<Identifier> onPick) {
        int gap = 4;
        int buttonWidth = Math.max(56, (groupRowWidth - gap * (ids.size() - 1)) / Math.max(1, ids.size()));
        for (int i = 0; i < ids.size(); i++) {
            Identifier id = ids.get(i);
            Button button = new Button(centerX + i * (buttonWidth + gap), y, buttonWidth, groupRowHeight,
                    groupLabel(id), () -> onPick.accept(id));
            button.style(Button.Style.SEED_CHOOSER);
            // `own` rather than `addWidget`: these belong to the page, so switching away
            // and back does not leave a second copy of the rows behind.
            own(button);
            target.add(button);
        }
    }

    private void refreshGroupState() {
        List<Identifier> themes = themeIds();
        for (int i = 0; i < groupThemeButtons.size() && i < themes.size(); i++) {
            groupThemeButtons.get(i).setActive(!themes.get(i).equals(selectedThemeId));
        }
        List<Identifier> categories = categoryIds();
        for (int i = 0; i < groupCategoryButtons.size() && i < categories.size(); i++) {
            groupCategoryButtons.get(i).setActive(!categories.get(i).equals(selectedCategoryId));
        }
    }

    private static String groupLabel(Identifier id) {
        return LevelPage.label(id.toString(), UNCATEGORIZED_KEY, UNCATEGORIZED_TEXT);
    }

    /**
     * Applies a group change: id, file and save directory all move together.
     *
     * <p>How the move is reported is the caller's business - the fields here only decide
     * <em>whether</em> something moved, which the save path also needs in order to delete
     * the file the level used to live in.
     */
    private boolean applyGroupChange(Path previousFile, Path newFile) {
        Identifier moved = LevelMove.idAfterMove(levelId, selectedThemeId, selectedCategoryId);
        if (moved == null || moved.equals(levelId)) {
            return false;
        }
        LevelMove.Result result = LevelMove.move(levelId, selectedThemeId, selectedCategoryId,
                previousFile, newFile, currentWorldDirectory());
        if (!result.ok()) {
            setStatus(GuiLang.raw("pvzce.editor.group_move_failed", "换组失败：")
                    + (result.problem() == null ? "" : result.problem()));
            return false;
        }
        levelId = result.id();
        setStatus(GuiLang.raw("pvzce.editor.group_moved", "已换组：") + levelId);
        return true;
    }

    /**
     * {@code saves/<world>} for the world the save move should look in, or {@code null}.
     *
     * <p>The client's current world is the one the level was opened from, so it is the one
     * the run save would be under. It is sanitized the way the server does it, so the path
     * this moves and the path the server writes are the same path.
     */
    private Path currentWorldDirectory() {
        String world = client.currentWorld();
        if (world == null || world.isBlank()) {
            return null;
        }
        String safe = world.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        return client.gameDir().resolve("saves").resolve(safe.isBlank() ? "world" : safe);
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    @Override
    public void render() {
        client.beginGuiView();
        renderBackground(0.07F, 0.09F, 0.11F);

        String header = GuiLang.raw("pvzce.editor", "关卡编辑器") + "   " + levelName
                + "   ·   " + levelId + "   ·   " + width + "×" + height;
        float headerScale = Math.min(1.05F, Math.max(0.66F, client.guiHeight() / 760F));
        // Drawn to the right of the action buttons, not on top of them: the old title
        // row and the button row shared one line and overlapped at any window size.
        float headerX = paletteX + actionButtonSpan + 16F;
        client.font().draw(header, headerX, actionY + actionH / 2F - 5F, headerScale, 1F, 1F, 1F, 1F);

        if (page.paletteKind != null) {
            drawPanel(paletteX, centerY, paletteW, centerH);
            // Inside the panel: the strip between the nav bar and the panel is already
            // taken by the page label, so a title drawn above it was clipped.
            client.font().draw(GuiLang.raw("pvzce.editor.palette", "可放置内容"),
                    paletteX + 4, centerY + centerH - 14F, 0.68F, 0.85F, 0.9F, 0.95F, 1F);
            drawPanel(centerX, centerY, centerW, centerH);
            drawPanel(sideX, centerY, sideW, centerH);
            renderBoard();
        } else {
            // Pages without a palette use the full width: the wave and music editors have
            // two columns of their own and read badly squeezed into the centre column.
            int[] area = fullContentArea();
            drawPanel(area[0], area[1], area[2], area[3]);
        }

        for (AbstractWidget widget : widgets) {
            widget.render(client);
        }

        drawPageLabels();

        if (!status.isEmpty() && System.nanoTime() < statusUntilNanos) {
            client.font().draw(status, paletteX + 6, actionY + actionH + 24F, 0.8F, 0.7F, 1F, 0.7F, 1F);
        }
    }

    private void drawPanel(int x, int y, int w, int h) {
        SpriteRenderer.solid(x, y, w, h, -0.3F, 0.1F, 0.12F, 0.14F, 0.9F);
    }

    private void drawPageLabels() {
        switch (page) {
            case TERRAIN -> client.font().draw(GuiLang.raw("pvzce.editor.tip.terrain",
                            "左键涂格，右键恢复草地"),
                    sideX + 4, centerY + centerH - 10F, 0.7F, 0.85F, 0.9F, 0.9F, 1F);
            case PLANT, ZOMBIE -> client.font().draw(GuiLang.raw("pvzce.editor.tip.entity",
                            "左键放置，右键移除"),
                    sideX + 4, centerY + centerH - 10F, 0.7F, 0.85F, 0.9F, 0.9F, 1F);
            case RULE -> client.font().draw(GuiLang.raw("pvzce.editor.tip.rule",
                            "改动立即生效，保存后写入关卡"),
                    centerX + 6, centerY + centerH + 4F, 0.72F, 0.85F, 0.9F, 0.9F, 1F);
            case WAVE -> {
                int total = 0;
                for (int i = 0; i < waveConfig.waves.size(); i++) {
                    total += waveConfig.totalZombies(i);
                }
                // One summary line in the strip above the table, not three lines stacked
                // inside it - they used to be drawn over the first table rows.
                int[] area = fullContentArea();
                float summaryY = area[1] + area[3] + 4F;
                client.font().draw("波次 " + waveConfig.waves.size()
                                + "　僵尸总数 " + total
                                + "　末波倍率 " + GuiText.formatFloat(waveConfig.intervalEndMultiplier),
                        area[0] + 4, summaryY, 0.74F, 1F, 1F, 1F, 1F);
                if (waveConfig.waves.isEmpty()) {
                    client.font().draw("还没有波次：点「自动填充」生成骨架，或「新增波次」逐条添加",
                            area[0] + 4, area[1] + area[3] / 2F, 0.85F, 1F, 0.85F, 0.5F, 1F);
                }
                if (waveDelayBox != null) {
                    client.font().draw("间隔 tick", waveDelayBox.x(),
                            waveDelayBox.y() + waveDelayBox.height() + 3F, 0.68F, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw("预警 tick", waveWarningBox.x(),
                            waveWarningBox.y() + waveWarningBox.height() + 3F, 0.68F, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw("出怪间隔 tick（空 = 默认）", waveIntervalBox.x(),
                            waveIntervalBox.y() + waveIntervalBox.height() + 3F,
                            0.68F, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw("组成（选中后用右侧数量框改）", waveEntryList.x(),
                            waveEntryList.y() + waveEntryList.height() + 4F, 0.7F, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw("可选僵尸", waveZombieList.x(),
                            waveZombieList.y() + waveZombieList.height() + 4F, 0.7F, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw("数量", waveCountBox.x(),
                            waveCountBox.y() + waveCountBox.height() + 3F, 0.68F, 0.9F, 0.9F, 0.9F, 1F);
                }
            }
            case MUSIC -> {
                int[] area = fullContentArea();
                client.font().draw("提示音 " + musicConfig.cues.size() + " 条　"
                                + "每条按 tick 触发，可选轨道 background / battle / menu / stinger",
                        area[0] + 4, area[1] + area[3] + 4F, 0.74F, 1F, 1F, 1F, 1F);
                // Field labels in the gaps the fields leave; without them three bare
                // numbers in a row say nothing about which is which.
                if (musicTickBox != null) {
                    client.font().draw("tick　　/　音量　/　淡入秒", musicTickBox.x(),
                            musicTickBox.y() + musicTickBox.height() + 3F, 0.68F, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw("事件（点选即应用）", musicEventList.x(),
                            musicEventList.y() + musicEventList.height() + 4F, 0.7F, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw("时间线（tick / 轨道 / 事件）", area[0] + 4,
                            musicCueList.y() + musicCueList.height() + 4F, 0.7F, 0.9F, 0.9F, 0.9F, 1F);
                }
                if (musicConfig.cues.isEmpty()) {
                    client.font().draw("时间线为空：这个关卡不会播放任何音乐",
                            area[0] + 4, area[1] + area[3] / 2F, 0.85F, 1F, 0.85F, 0.5F, 1F);
                }
            }
            case CARDS -> {
                int[] area = fullContentArea();
                float labelY = cardListTop - 17F;
                float colW = (area[2] - 10) / 2F;
                int levelCards = cardPoolConfig.pool.size();
                int slots = Math.max(levelCards, Math.max(0, cardPoolConfig.maxSeedSlots));
                int free = Math.max(0, slots - levelCards);
                float summaryY = area[1] + area[3] + 4F;
                client.font().draw("总卡槽 " + slots + "　关卡固定 " + levelCards
                                + "　玩家可选 " + free, area[0] + 2, summaryY, 0.74F, 1F, 1F, 1F, 1F);
                if (free == 0) {
                    // On the summary line, not above the column labels: the note and the
                    // column title landed on the same pixels.
                    client.font().draw("（关卡卡槽已占满总卡槽：玩家拿到固定卡组，没有可选内容）",
                            area[0] + 2 + client.font().width(
                                    "总卡槽 " + slots + "　关卡固定 " + levelCards
                                            + "　玩家可选 " + free, 0.74F) + 10F,
                            summaryY, 0.72F, 1F, 0.85F, 0.45F, 1F);
                }
                client.font().draw("关卡卡槽（顺序即游戏内卡槽顺序）", area[0] + 2, labelY, 0.7F,
                        1F, 0.9F, 0.6F, 1F);
                client.font().draw("全部卡（按类别）", area[0] + colW + 12, labelY, 0.7F,
                        0.9F, 0.9F, 0.9F, 1F);
            }
            case UNLOCK -> renderUnlockPage();
            case INFO -> {
                // Each label sits in the gap above its own field. The offsets used to be
                // hand-picked constants that put "初始阳光" on top of the sun field.
                if (nameBox != null) {
                    float labelScale = 0.75F;
                    client.font().draw(GuiLang.raw("pvzce.editor.level_name", "关卡名称"),
                            centerX, nameBox.y() + nameBox.height() + 6F, labelScale, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw(GuiLang.raw("pvzce.editor.level_desc", "关卡描述"),
                            centerX, descriptionBox.y() + descriptionBox.height() + 6F,
                            labelScale, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw(GuiLang.raw("pvzce.editor.initial_sun", "初始阳光"),
                            centerX, sunBox.y() + sunBox.height() + 6F, labelScale, 0.9F, 0.9F, 0.9F, 1F);
                    float rewardLabelY = rewardUnlockBox.y() + rewardUnlockBox.height() + 6F;
                    client.font().draw(GuiLang.raw("pvzce.editor.reward_unlock", "首通解锁卡（留空则不给卡）"),
                            centerX, rewardLabelY, labelScale, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw(GuiLang.raw("pvzce.editor.reward_repeat", "重复通关金币"),
                            centerX, repeatCoinsBox.y() + repeatCoinsBox.height() + 6F,
                            labelScale, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw(GuiLang.raw("pvzce.editor.reward_drop_chance", "僵尸掉币概率 0~1"),
                            coinDropChanceBox.x(), coinDropChanceBox.y() + coinDropChanceBox.height() + 6F,
                            labelScale, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw(GuiLang.raw("pvzce.editor.reward_drop_amount", "掉币数量"),
                            coinDropAmountBox.x(), coinDropAmountBox.y() + coinDropAmountBox.height() + 6F,
                            labelScale, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw(GuiLang.raw("pvzce.editor.reward_drop_coin", "掉哪种币"),
                            coinDropBox.x(), coinDropBox.y() + coinDropBox.height() + 6F,
                            labelScale, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw(GuiLang.raw("pvzce.editor.readonly_id", "关卡 ID 创建后不可修改")
                                    + "：" + levelId,
                            centerX, sunBox.y() - 22F, labelScale, 0.78F, 0.84F, 0.84F, 1F);
                    // The group rows: picking another one and saving moves the level, which
                    // renames its id - the one rename the info page is allowed to make.
                    client.font().draw(GuiLang.raw("pvzce.editor.theme", "主题"),
                            centerX, groupThemeY + groupRowHeight + 4F, labelScale, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw(GuiLang.raw("pvzce.editor.category", "类别"),
                            centerX, groupCategoryY + groupRowHeight + 4F, labelScale, 0.9F, 0.9F, 0.9F, 1F);
                    client.font().draw(GuiLang.raw("pvzce.editor.group_move_hint",
                                    "换组并保存会重命名关卡 ID，并移动关卡文件与该关存档"),
                            centerX, groupCategoryY - 20F, 0.68F, 0.85F, 0.8F, 0.6F, 1F);
                }
            }
            default -> {
            }
        }
    }

    /**
     * Draws the board through the game's world projection and plays real animations.
     *
     * <p>The footprint comes from the same {@link LevelStage#board} call the in-game
     * camera makes for this rectangle, so a cell has the same shape here as in play
     * and {@link #cellAtCursor} is the exact inverse of what is drawn. The previous
     * canvas stretched every cell to fit and drew sprites as flat GUI quads, which is
     * why placed plants were stills and why a plant's size did not match the game.
     */
    private void renderBoard() {
        LevelStage.Board board = board();
        float boardX = centerX + board.x();
        float boardY = centerY + board.y();
        float cellH = Math.max(0.0001F, board.cellHeight());

        client.clipping().push(boardX, boardY, board.width(), board.height());
        try {
            client.beginOverlayWorldView(boardX, boardY, board.width(), board.height(),
                    0F, boardColumns(), 0F, boardRows());
            SceneTileRenderer.render(client, boardColumns(), boardRows(),
                    (x, y) -> {
                        String id = sceneAt(x, y);
                        return id == null ? "pvzce:grass" : id;
                    }, 1F, 0F);
            for (CanvasEntity entry : canvasEntities) {
                renderCanvasEntity(entry, cellH);
            }
            // Unpainted cells are marked after the tiles so the author can see the
            // holes a level JSON would really have there.
            for (int y = 0; y < boardRows(); y++) {
                for (int x = 0; x < boardColumns(); x++) {
                    if (sceneAt(x, y) == null) {
                        client.drawSolid(x, y, 1F, 1F, 6F, 0.55F, 0.55F, 0.55F, 0.35F);
                    }
                }
            }
            int[] hover = cellAtCursor();
            if (hover != null) {
                client.drawSolid(hover[0], hover[1], 1F, 1F, 7F, 1F, 1F, 0.2F, 0.35F);
            }
            client.beginGuiView();
        } finally {
            client.clipping().pop();
        }
    }

    private void renderCanvasEntity(CanvasEntity entry, float cellHeight) {
        String sceneId = sceneAt(entry.x(), entry.y());
        var element = sceneId == null ? null : BuiltInRegistries.SCENE_ELEMENTS.get(Identifier.tryParse(sceneId));
        float height = element == null ? 0F : element.heightAt(entry.x() + 0.5F, boardColumns());
        entry.entity.update(entry.x() + 0.5F, entry.y() + 0.5F, 100, "idle", height);
        entry.entity.playAnimation("idle");
        if (client.animations() != null && client.animations().render(entry.entity)) {
            return;
        }
        // No animation resource: fall back to the sprite with the same visuals table
        // the board uses, so the size still matches the game.
        EntityVisuals.Visuals visuals = EntityVisuals.of(entry.kind());
        client.drawTexture(EntityTextures.forEntity(entry.id()),
                entry.x() + 0.5F - visuals.spriteWidth() / 2F,
                entry.y() + 0.5F - visuals.spriteHeight() / 2F + height,
                visuals.spriteWidth(), visuals.spriteHeight(), visuals.baseZ(), 1F, 1F, 1F, 1F);
    }

    /** The board footprint inside the canvas, in GUI pixels. */
    private LevelStage.Board board() {
        return LevelStage.board(Math.max(1, centerW), Math.max(1, centerH), boardColumns(), boardRows());
    }

    private int boardRows() {
        return Math.max(1, height);
    }

    private int boardColumns() {
        return Math.max(1, width);
    }

    /**
     * The board cell under the cursor, or {@code null} outside the board rectangle.
     *
     * <p>Mirrors {@link #renderBoard}: the same footprint, the same clipping rect, the
     * same cell size. The old editor divided the canvas by {@code cellSize()} while a
     * uniform 6x6 tile pass drew with a different margin, so the outermost cells were
     * systematically offset from their highlight.
     */
    private int[] cellAtCursor() {
        LevelStage.Board board = board();
        float boardX = centerX + board.x();
        float boardY = centerY + board.y();
        double guiX = client.guiMouseX(client.window().cursorX());
        double guiY = client.guiMouseY(client.window().cursorY());
        if (guiX < boardX || guiX >= boardX + board.width()
                || guiY < boardY || guiY >= boardY + board.height()) {
            return null;
        }
        int cellX = (int) Math.floor((guiX - boardX) / board.cellWidth());
        int cellY = (int) Math.floor((guiY - boardY) / board.cellHeight());
        if (cellX < 0 || cellX >= boardColumns() || cellY < 0 || cellY >= boardRows()) {
            return null;
        }
        return new int[]{cellX, cellY};
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    private void placeAt(int cellX, int cellY) {
        if (cellX < 0 || cellX >= width || cellY < 0 || cellY >= height) {
            return;
        }
        String pos = cellX + "," + cellY;
        switch (page) {
            case TERRAIN -> {
                removePositionFromAll(pos);
                scene.computeIfAbsent(selectedSceneId, ignored -> new ArrayList<>()).add(pos);
            }
            case PLANT, ZOMBIE -> {
                if (selectedEntityId.isEmpty()) {
                    return;
                }
                initialEntities.removeIf(entity -> entity.get("x").getAsInt() == cellX
                        && entity.get("y").getAsInt() == cellY);
                JsonObject entity = new JsonObject();
                entity.addProperty("kind", page == Page.ZOMBIE ? "zombie" : "plant");
                entity.addProperty("id", selectedEntityId);
                entity.addProperty("x", cellX);
                entity.addProperty("y", cellY);
                initialEntities.add(entity);
                syncCanvasEntities();
            }
            default -> {
            }
        }
    }

    private void removeAt(int cellX, int cellY) {
        String pos = cellX + "," + cellY;
        boolean changed = initialEntities.removeIf(entity -> entity.get("x").getAsInt() == cellX
                && entity.get("y").getAsInt() == cellY);
        removePositionFromAll(pos);
        scene.computeIfAbsent("pvzce:grass", ignored -> new ArrayList<>()).add(pos);
        if (changed) {
            syncCanvasEntities();
        }
    }

    private void removePositionFromAll(String pos) {
        for (List<String> positions : scene.values()) {
            positions.remove(pos);
        }
    }

    /** The element at a cell, or {@code null} when the author has not painted it. */
    private String sceneAt(int x, int y) {
        String pos = x + "," + y;
        for (var entry : scene.entrySet()) {
            if (entry.getValue().contains(pos)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void syncCanvasEntities() {
        releaseCanvasAnimations();
        canvasEntities.clear();
        for (JsonObject json : initialEntities) {
            String kind = json.has("kind") ? json.get("kind").getAsString() : "plant";
            ClientEntity entity = new ClientEntity(-1 - canvasEntities.size(),
                    kind.startsWith("z") ? "zombie" : "plant", json.get("id").getAsString(),
                    json.get("x").getAsInt() + 0.5F, json.get("y").getAsInt() + 0.5F,
                    100, 1, "idle", 0F, "");
            // The animation manager is injected by ClientLevel on spawn; the editor's
            // preview entities never come from a spawn packet, so without this their
            // playAnimation() was a no-op and every preset stayed a static sprite.
            if (client.animations() != null) {
                entity.attachAnimationManager(client.animations());
            }
            canvasEntities.add(new CanvasEntity(json, entity));
        }
    }

    /**
     * Drops the editor's animation playbacks.
     *
     * <p>Without this every board edit left strongly referenced entities in the
     * animation manager, advanced on every frame for the rest of the session - the
     * same leak the seed chooser's preview had to fix.
     */
    private void releaseCanvasAnimations() {
        if (client.animations() == null) {
            return;
        }
        for (CanvasEntity entry : canvasEntities) {
            client.animations().release(entry.entity);
        }
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    private void collectEditableFields() {
        if (nameBox != null) {
            levelName = nameBox.value().isBlank() ? levelId.path() : nameBox.value().trim();
        }
        if (descriptionBox != null) {
            description = descriptionBox.value().trim();
        }
        if (sunBox != null) {
            initialSun = GuiText.parseInt(sunBox.value(), initialSun, 0, 100_000);
            sunBox.setValue(String.valueOf(initialSun), false);
            rewardUnlock = rewardUnlockBox.value().trim();
            rewardRepeatCoins = GuiText.parseInt(repeatCoinsBox.value(), rewardRepeatCoins, 0, 100_000);
            rewardCoinDropChance = GuiText.parseFloat(coinDropChanceBox.value(), rewardCoinDropChance, 0F, 1F);
            rewardCoinDropAmount = GuiText.parseInt(coinDropAmountBox.value(), rewardCoinDropAmount, 0, 1000);
            rewardCoinDrop = coinDropBox.value().trim();
        }
        maxSeedSlots = clamp(cardPoolConfig.maxSeedSlots, 0, CardPoolEditorDialog.MAX_SEED_SLOTS_LIMIT);
    }

    private void save() {
        if (saveToDisk()) {
            client.connection().send(new CommandC2S("/reload"));
            setStatus(GuiLang.raw("pvzce.editor.saved", "已保存：") + levelId);
        }
    }

    /**
     * Writes the level file.
     *
     * <p>Split from {@link #save()} so a test run can write once and let
     * {@code testEditedLevel} own the reload: the save path used to send its own
     * {@code /reload} and the test path then sent a second one.
     *
     * @return true when the file was written
     */
    private boolean saveToDisk() {
        collectEditableFields();
        JsonObject root = buildLevelJson(sourceJson, levelId, levelName, description, width, height,
                scene, initialEntities, rules, waveConfig, cardPoolConfig.pool, maxSeedSlots,
                initialSun, musicConfig.toJson(), currentRewards(), unlockJson());
        Path previousFile = sourceFile;
        Path levelFile = resolveSaveFile();
        try {
            Files.createDirectories(levelFile.getParent());
            Files.writeString(levelFile, root.toString());
            Path pack = client.gameDir().resolve("datapacks/user_levels");
            Path mcmeta = pack.resolve("pack.mcmeta");
            if (!Files.exists(mcmeta)) {
                Files.writeString(mcmeta, "{\"pack\":{\"pack_format\":1,\"description\":\"User levels\"}}");
            }
            sourceFile = levelFile;
            sourceJson = root;
            // Only now that the level is safely at its new path is the move applied - and
            // the old file is only removed once the id really changed, so a refused move
            // leaves the level exactly where it was.
            boolean movedGroup = applyGroupChange(previousFile, levelFile);
            if (!movedGroup && previousFile != null && !previousFile.equals(levelFile)) {
                Files.deleteIfExists(previousFile);
                sourceFile = null;
            }
            return true;
        } catch (IOException e) {
            setStatus(GuiLang.raw("pvzce.editor.save_failed", "保存失败：") + e.getMessage());
            return false;
        }
    }

    /**
     * The level JSON the editor writes.
     *
     * <p>Starts from {@code previous} - the JSON as loaded - so a field the editor does
     * not model (a newer version's field, another tool's metadata, the level's teams)
     * survives an edit. Rewriting the file from only the modelled fields is how an
     * editor quietly deletes things it never understood.
     *
     * <p>Static and free of the screen so the merge can be tested without a client.
     */
    static JsonObject buildLevelJson(JsonObject previous, Identifier levelId, String levelName,
                                     String description, int width, int height,
                                     Map<String, List<String>> scene, List<JsonObject> initialEntities,
                                     JsonObject rules, WaveEditorModel.Config waveConfig,
                                     List<String> pool, int maxSeedSlots, int initialSun,
                                     JsonObject music) {
        return buildLevelJson(previous, levelId, levelName, description, width, height, scene,
                initialEntities, rules, waveConfig, pool, maxSeedSlots, initialSun, music,
                LevelRewards.DEFAULT);
    }

    /**
     * The same merge, with the level's rewards.
     *
     * <p>The editor owns the whole {@code rewards} block: it has exactly four fields,
     * all of them on the info page, so writing it whole cannot drop anything the
     * editor does not understand. A hand-written {@code first_clear} list with several
     * entries is normalised to at most one unlock plus one coin entry, which the info
     * page says out loud.
     */
    static JsonObject buildLevelJson(JsonObject previous, Identifier levelId, String levelName,
                                     String description, int width, int height,
                                     Map<String, List<String>> scene, List<JsonObject> initialEntities,
                                     JsonObject rules, WaveEditorModel.Config waveConfig,
                                     List<String> pool, int maxSeedSlots, int initialSun,
                                     JsonObject music, LevelRewards rewards) {
        return buildLevelJson(previous, levelId, levelName, description, width, height, scene,
                initialEntities, rules, waveConfig, pool, maxSeedSlots, initialSun, music, rewards,
                null);
    }

    /**
     * The same merge, with the level's unlock block.
     *
     * <p>{@code unlock} is owned by the {@code 解锁} page the same way {@code rewards} is
     * owned by the info page - the page covers every field the format has - so writing it
     * whole cannot drop anything. A {@code null} block means "the editor did not touch it",
     * which leaves a hand-written condition in place.
     */
    static JsonObject buildLevelJson(JsonObject previous, Identifier levelId, String levelName,
                                     String description, int width, int height,
                                     Map<String, List<String>> scene, List<JsonObject> initialEntities,
                                     JsonObject rules, WaveEditorModel.Config waveConfig,
                                     List<String> pool, int maxSeedSlots, int initialSun,
                                     JsonObject music, LevelRewards rewards, JsonObject unlock) {
        JsonObject root = previous == null ? new JsonObject() : previous.deepCopy();
        root.addProperty("id", levelId.toString());
        root.addProperty("name", levelName);
        root.addProperty("description", description);
        root.addProperty("width", width);
        root.addProperty("height", height);
        JsonObject sceneJson = new JsonObject();
        for (Map.Entry<String, List<String>> entry : scene.entrySet()) {
            JsonArray positions = new JsonArray();
            for (String pos : new LinkedHashSet<>(entry.getValue())) {
                positions.add(pos);
            }
            if (!positions.isEmpty()) {
                sceneJson.add(entry.getKey(), positions);
            }
        }
        root.add("scene", sceneJson);
        JsonArray entities = new JsonArray();
        for (JsonObject entity : initialEntities) {
            entities.add(entity);
        }
        root.add("initial_entities", entities);
        root.add("rules", rules == null ? new JsonObject() : rules);
        JsonObject waveJson = waveConfig.toJson();
        root.addProperty("wave_interval_end_multiplier", waveConfig.intervalEndMultiplier);
        root.add("waves", waveJson.getAsJsonArray("waves"));
        if (!root.has("teams")) {
            root.add("teams", defaultTeams());
            root.addProperty("win_team", "pvzce:plant_team");
        }
        JsonArray slots = new JsonArray();
        // The card list is the whole contract now: these cards are the level's and are
        // pinned, and max_seed_slots (raised to fit them) says how many slots there are.
        for (String slot : pool) {
            slots.add(slot);
        }
        root.add("slots", slots);
        // A level saved by an older editor may still carry the split, which would pin a
        // different set than `slots`; drop it so the file has one answer.
        root.remove("seed_selection");
        root.addProperty("max_seed_slots", maxSeedSlots);
        if (!root.has("unlock_resources")) {
            // Not to be confused with the ``unlock`` block below: this one is about which
            // resources the level grants, and predates unlock conditions entirely.
            JsonObject resources = new JsonObject();
            resources.addProperty("pvzce:sun", true);
            root.add("unlock_resources", resources);
        }
        root.addProperty("initial_sun", initialSun);
        root.add("rewards", rewardsJson(rewards == null ? LevelRewards.DEFAULT : rewards));
        if (unlock != null) {
            // An empty block is removed rather than written: "no conditions" and "an
            // unlock block that says nothing" should not both exist in the data.
            boolean empty = !unlock.has("cost") && !unlock.has("hidden")
                    && (!unlock.has("requires") || unlock.getAsJsonArray("requires").isEmpty());
            if (empty) {
                root.remove("unlock");
            } else {
                root.add("unlock", unlock);
            }
        }
        root.add("music", music == null ? new JsonObject() : music);
        return root;
    }

    /** The rewards block as JSON, in the shape {@link LevelRewards#CODEC} reads back. */
    private static JsonObject rewardsJson(LevelRewards rewards) {
        JsonObject out = new JsonObject();
        JsonArray firstClear = new JsonArray();
        for (LevelRewards.Reward reward : rewards.firstClear()) {
            firstClear.add(rewardJson(reward));
        }
        JsonArray repeat = new JsonArray();
        for (LevelRewards.Reward reward : rewards.repeat()) {
            repeat.add(rewardJson(reward));
        }
        out.add("first_clear", firstClear);
        out.add("repeat", repeat);
        out.addProperty("coin_drop", rewards.coinDrop().toString());
        out.addProperty("coin_drop_chance", rewards.coinDropChance());
        out.addProperty("coin_drop_amount", rewards.coinDropAmount());
        return out;
    }

    private static JsonObject rewardJson(LevelRewards.Reward reward) {
        JsonObject out = new JsonObject();
        out.addProperty("type", reward.type());
        reward.id().ifPresent(id -> out.addProperty("id", id.toString()));
        if (reward.amount() > 0) {
            out.addProperty("amount", reward.amount());
        }
        return out;
    }

    /** The rewards the info page currently describes. */
    private LevelRewards currentRewards() {
        List<LevelRewards.Reward> firstClear = new ArrayList<>();
        Identifier unlock = Identifier.tryParse(rewardUnlock);
        if (unlock != null) {
            firstClear.add(LevelRewards.Reward.unlock(unlock));
        }
        List<LevelRewards.Reward> repeat = rewardRepeatCoins > 0
                ? List.of(LevelRewards.Reward.coins(rewardRepeatCoins))
                : List.of();
        Identifier drop = Identifier.tryParse(rewardCoinDrop);
        if (drop == null) {
            drop = LevelRewards.DEFAULT_COIN_DROP;
        }
        return new LevelRewards(firstClear, repeat, rewardCoinDropChance, drop, rewardCoinDropAmount);
    }

    /**
     * Reads the editable half of a level's {@code rewards} block.
     *
     * <p>Only the numbers the info page shows come back: first clear keeps its first
     * unlock and drops the rest with a log line, because normalising silently is how
     * an author loses a reward they wrote by hand.
     */
    /**
     * Reads the level's {@code unlock} block into the page's four fields.
     *
     * <p>A requirement the page cannot express (a coin threshold, or a type a later
     * version added) is kept in {@link #unlockExtra} and written back on save, so opening
     * a level in the editor never quietly drops a condition the author wrote by hand.
     */
    private void readUnlock(JsonObject sourceJson) {
        unlockLevels = "";
        unlockCards = "";
        unlockCost = 0;
        unlockHidden = false;
        unlockExtra = new JsonArray();
        if (!sourceJson.has("unlock") || !sourceJson.get("unlock").isJsonObject()) {
            return;
        }
        JsonObject unlock = sourceJson.getAsJsonObject("unlock");
        unlockHidden = unlock.has("hidden") && unlock.get("hidden").getAsBoolean();
        if (unlock.has("cost") && unlock.get("cost").isJsonPrimitive()) {
            unlockCost = Math.max(0, unlock.get("cost").getAsInt());
        }
        if (!unlock.has("requires") || !unlock.get("requires").isJsonArray()) {
            return;
        }
        List<String> levels = new ArrayList<>();
        List<String> cards = new ArrayList<>();
        for (JsonElement element : unlock.getAsJsonArray("requires")) {
            if (!element.isJsonObject()) {
                unlockExtra.add(element);
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String type = entry.has("type") ? entry.get("type").getAsString() : "";
            String id = entry.has("id") && entry.get("id").isJsonPrimitive()
                    ? entry.get("id").getAsString() : "";
            if ("level".equals(type) && !id.isEmpty()) {
                levels.add(id);
            } else if ("card".equals(type) && !id.isEmpty()) {
                cards.add(id);
            } else {
                // A coin threshold or an unknown type: preserved verbatim, not editable here.
                unlockExtra.add(element.deepCopy());
            }
        }
        unlockLevels = String.join(", ", levels);
        unlockCards = String.join(", ", cards);
    }

    /**
     * The {@code unlock} block for the level file, or an empty object when there is none.
     *
     * <p>Whole-block ownership like {@code rewards}: the page covers every field the
     * format has, and anything it could not represent is kept in {@code unlockExtra}.
     */
    private JsonObject unlockJson() {
        JsonArray requires = new JsonArray();
        for (String id : splitIdList(unlockLevels)) {
            requires.add(requirementJson("level", id));
        }
        for (String id : splitIdList(unlockCards)) {
            requires.add(requirementJson("card", id));
        }
        for (JsonElement extra : unlockExtra) {
            requires.add(extra.deepCopy());
        }
        JsonObject out = new JsonObject();
        out.add("requires", requires);
        if (unlockCost > 0) {
            out.addProperty("cost", unlockCost);
        }
        if (unlockHidden) {
            out.addProperty("hidden", true);
        }
        return out;
    }

    private static JsonObject requirementJson(String type, String id) {
        JsonObject entry = new JsonObject();
        entry.addProperty("type", type);
        entry.addProperty("id", id);
        return entry;
    }

    /** Splits a comma-separated id list, dropping blanks and duplicates. */
    private static List<String> splitIdList(String value) {
        List<String> ids = new ArrayList<>();
        if (value == null) {
            return ids;
        }
        for (String part : value.split(",")) {
            String id = part.trim();
            if (!id.isEmpty() && !ids.contains(id)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private void readRewards(JsonObject sourceJson) {
        rewardUnlock = "";
        rewardRepeatCoins = LevelRewards.DEFAULT_REPEAT_COINS;
        rewardCoinDropChance = LevelRewards.DEFAULT_COIN_DROP_CHANCE;
        rewardCoinDrop = LevelRewards.DEFAULT_COIN_DROP.toString();
        rewardCoinDropAmount = LevelRewards.DEFAULT_COIN_DROP_AMOUNT;
        if (!sourceJson.has("rewards") || !sourceJson.get("rewards").isJsonObject()) {
            return;
        }
        JsonObject rewards = sourceJson.getAsJsonObject("rewards");
        if (rewards.has("first_clear") && rewards.get("first_clear").isJsonArray()) {
            JsonArray firstClear = rewards.getAsJsonArray("first_clear");
            for (JsonElement element : firstClear) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                if (entry.has("id") && entry.get("id").isJsonPrimitive()) {
                    if (rewardUnlock.isEmpty()) {
                        rewardUnlock = entry.get("id").getAsString();
                    } else {
                        System.err.println("[PVZCE] Level grants more than one card on a first clear;"
                                + " the editor edits the first one and keeps the rest only until the"
                                + " next save: " + entry.get("id").getAsString());
                    }
                }
            }
        }
        if (rewards.has("repeat") && rewards.get("repeat").isJsonArray()) {
            int coins = 0;
            for (JsonElement element : rewards.getAsJsonArray("repeat")) {
                if (element.isJsonObject() && element.getAsJsonObject().has("amount")) {
                    coins += element.getAsJsonObject().get("amount").getAsInt();
                }
            }
            rewardRepeatCoins = Math.max(0, coins);
        }
        if (rewards.has("coin_drop_chance")) {
            rewardCoinDropChance = com.pvzce.common.util.MathUtil.clamp(
                    rewards.get("coin_drop_chance").getAsFloat(), 0F, 1F);
        }
        if (rewards.has("coin_drop") && rewards.get("coin_drop").isJsonPrimitive()) {
            rewardCoinDrop = rewards.get("coin_drop").getAsString();
        }
        if (rewards.has("coin_drop_amount")) {
            rewardCoinDropAmount = Math.max(0, rewards.get("coin_drop_amount").getAsInt());
        }
    }

    private static JsonArray defaultTeams() {
        JsonArray teams = new JsonArray();
        JsonObject plantTeam = new JsonObject();
        plantTeam.addProperty("id", "pvzce:plant_team");
        plantTeam.addProperty("name", "植物方");
        plantTeam.addProperty("win_condition", "survive_waves");
        JsonObject zombieTeam = new JsonObject();
        zombieTeam.addProperty("id", "pvzce:zombie_team");
        zombieTeam.addProperty("name", "僵尸方");
        zombieTeam.addProperty("win_condition", "plant_side_lost");
        teams.add(plantTeam);
        teams.add(zombieTeam);
        return teams;
    }

    /**
     * Where this level is written.
     *
     * <p>A level opened from the user-level pack is written back to the file it came
     * from. Deriving the path from a fresh guess is what made an edited level
     * unreachable: the old editor always wrote {@code <name>.json} under its own pack
     * while the id it opened stayed fixed, so the file and the id disagreed and the
     * next open found nothing. A built-in level that cannot be written in place gets a
     * user-level override of the same id instead.
     */
    private Path resolveSaveFile() {
        if (sourceFile != null) {
            return sourceFile;
        }
        return client.gameDir().resolve("datapacks/user_levels")
                .resolve("data").resolve(levelId.namespace()).resolve("pvzce/levels")
                .resolve(levelId.path() + ".json");
    }

    /**
     * Plays the level as saved, through the level list's own flow.
     *
     * <p>This used to send {@code /reload} and then start the level directly, which skipped
     * the seed chooser: testing a level and opening it from the list behaved differently.
     * Both now save, reload and hand over to the chooser; {@code testEditedLevel} waits for
     * the reloaded level list so the chooser sees the saved definition rather than the one
     * from before the save.
     */
    private void test() {
        if (!saveToDisk()) {
            return;
        }
        // One reload, owned by the client so it can wait for the refreshed level list
        // before opening the chooser.
        client.testEditedLevel(levelId.toString());
        releaseCanvasAnimations();
        client.closeScreen();
    }

    private void setStatus(String message) {
        status = message;
        statusUntilNanos = System.nanoTime() + 4_000_000_000L;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        if (modalDialog() != null) {
            return;
        }
        int[] cell = cellAtCursor();
        if (cell != null && (page == Page.TERRAIN || page == Page.PLANT || page == Page.ZOMBIE)) {
            if (button == 0) {
                placeAt(cell[0], cell[1]);
            } else if (button == 1) {
                removeAt(cell[0], cell[1]);
            }
            return;
        }
    }

    @Override
    public void tick() {
        if (palette != null) {
            selectFromPalette(palette.selected());
        }
        if (page == Page.RULE && ruleList != null) {
            Identifier selected = ruleList.selected();
            if (selected != null && !selected.equals(currentRule)) {
                selectRule(selected);
            }
        }
        if (page == Page.WAVE && waveList != null) {
            // The detail panel follows the table's selection; comparing against the wave
            // it was last built for is what makes this cheap enough to run every frame.
            WaveEditorModel.WaveModel selected = waveList.selected();
            if (selected != lastWaveShown) {
                lastWaveShown = selected;
                refreshWaveDetail();
            }
        }
        if (page == Page.MUSIC) {
            if (musicCueList != null) {
                MusicEditorModel.CueModel selected = musicCueList.selected();
                if (selected != lastMusicCueShown) {
                    lastMusicCueShown = selected;
                    refreshMusicDetail();
                }
            }
            applyMusicEventChoice();
        }
    }

    /**
     * Applies the event list's selection to the cue being edited.
     *
     * <p>The list is not a form field with a commit, it is a picker: choosing
     * "（无 / 停止）" clears the event and sets stop, choosing a music event sets it and
     * clears stop. Guarded against the same choice so a click that lands on the already
     * selected row does not fight the author's own toggle edits.
     */
    private void applyMusicEventChoice() {
        MusicEditorModel.CueModel cue = currentMusicCue();
        String chosen = musicEventList == null ? null : musicEventList.selected();
        if (cue == null || chosen == null || chosen.equals(lastMusicEventChoice)) {
            return;
        }
        lastMusicEventChoice = chosen;
        if (MusicEditorModel.STOP_LABEL.equals(chosen)) {
            cue.event = "";
            cue.stop = true;
        } else {
            cue.event = chosen;
            cue.stop = false;
        }
        refreshMusicDetail();
        refreshMusicCueList();
    }

    @Override
    public void keyPressed(int key) {
        if (modalDialog() != null) {
            super.keyPressed(key);
            return;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            releaseCanvasAnimations();
            client.closeScreen();
            return;
        }
        // Delete removes the entity under the cursor, so placing a row does not need
        // a round trip through right-click.
        if (key == GLFW.GLFW_KEY_DELETE && (page == Page.PLANT || page == Page.ZOMBIE)) {
            int[] cell = cellAtCursor();
            if (cell != null) {
                removeAt(cell[0], cell[1]);
            }
            return;
        }
        super.keyPressed(key);
    }

    @Override
    public void requestClose() {
        releaseCanvasAnimations();
        super.requestClose();
    }

    // ------------------------------------------------------------------
    // Development smoke hooks
    // ------------------------------------------------------------------

    /**
     * Switches pages by the lang key suffix, for smoke runs and screenshots.
     *
     * <p>A smoke run cannot click, so the page it wants to photograph has to be
     * reachable by name. Unknown names are ignored rather than throwing: a smoke
     * property is a debugging aid, not a contract.
     */
    public void showPageForSmoke(String key) {
        for (Page candidate : PAGES) {
            if (candidate.key.equals(key)) {
                switchPage(candidate);
                return;
            }
        }
    }

    /** The wave config, so a smoke run can open the wave table on the same instance. */
    public WaveEditorModel.Config waveConfigForSmoke() {
        return waveConfig;
    }

    /** Saves without a click, so a smoke run can verify the write round trip. */
    public void saveForSmoke() {
        save();
    }

    /** Places presets without a click, so a smoke run can photograph the canvas. */
    public void placeForSmoke(String kind, String id, int cellX, int cellY) {
        selectedEntityId = id;
        page = kind.startsWith("z") ? Page.ZOMBIE : Page.PLANT;
        placeAt(cellX, cellY);
    }
}
