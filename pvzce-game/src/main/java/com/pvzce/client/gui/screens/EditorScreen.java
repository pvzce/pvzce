package com.pvzce.client.gui.screens;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.AbstractSelectionList;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.EditBox;
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

/** In-game level editor (M4 "usable" level). */
public final class EditorScreen extends Screen {
    private int panelX;
    private int panelW;
    private int itemsX;
    private int itemsW;
    private int canvasX;
    private int canvasY;
    private int canvasW;
    private int canvasH;

    private final String sourceLevelId;
    private String levelId;
    private String saveName;
    private int width = 9;
    private int height = 5;
    private final Map<String, List<String>> scene = new LinkedHashMap<>();
    private final List<JsonObject> initialEntities = new ArrayList<>();
    private final JsonObject rules = new JsonObject();
    private final WaveEditorDialog.Config waveConfig = new WaveEditorDialog.Config();
    private final MusicEditorDialog.Config musicConfig = new MusicEditorDialog.Config();
    private final CardPoolEditorDialog.Config cardPoolConfig = new CardPoolEditorDialog.Config();
    private WaveEditorDialog waveDialog;
    private MusicEditorDialog musicDialog;
    private CardPoolEditorDialog cardPoolDialog;
    private int maxSeedSlots = LevelDef.DEFAULT_MAX_SEED_SLOTS;

    private String category = "scene";
    private String selectedId = "pvzce:grass";
    private AbstractSelectionList<String> itemList;

    public EditorScreen(PvzceClient client, String levelId) {
        super(client);
        this.sourceLevelId = levelId;
        this.levelId = levelId;
        load();
    }

    private void load() {
        Identifier id = Identifier.tryParse(sourceLevelId);
        boolean loaded = false;
        boolean hasSlotsField = false;
        if (id != null) {
            try {
                var resource = client.resources().getData(Identifier.of(id.namespace(), "pvzce/levels/" + id.path()));
                if (resource.isPresent()) {
                    loaded = true;
                    JsonObject root = JsonParser.parseString(resource.get().readString()).getAsJsonObject();
                    hasSlotsField = root.has("slots");
                    width = root.has("width") ? root.get("width").getAsInt() : 9;
                    height = root.has("height") ? root.get("height").getAsInt() : 5;
                    if (root.has("scene")) {
                        for (var entry : root.getAsJsonObject("scene").entrySet()) {
                            List<String> positions = new ArrayList<>();
                            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                                positions.add(element.getAsString());
                            }
                            scene.put(entry.getKey(), positions);
                        }
                    }
                    if (root.has("initial_entities")) {
                        for (JsonElement element : root.getAsJsonArray("initial_entities")) {
                            initialEntities.add(element.getAsJsonObject());
                        }
                    }
                    if (root.has("rules")) {
                        for (var entry : root.getAsJsonObject("rules").entrySet()) {
                            rules.add(entry.getKey(), entry.getValue());
                        }
                    }
                    waveConfig.replaceWith(WaveEditorDialog.Config.fromJson(root));
                    cardPoolConfig.replaceWith(CardPoolEditorDialog.Config.fromJson(root));
                    musicConfig.replaceWith(MusicEditorDialog.Config.fromJson(root));
                }
            } catch (IOException e) {
                System.err.println("Failed to load level for editor: " + e.getMessage());
            }
        }
        if (!loaded) {
            // A brand-new custom level gets one explicit grasswalk cue; users
            // can delete it to produce an intentionally silent level.
            MusicEditorDialog.CueModel cue = new MusicEditorDialog.CueModel();
            cue.event = "pvzce:music/grasswalk";
            musicConfig.cues.add(cue);
        }
        if (!scene.containsKey("pvzce:grass")) {
            scene.put("pvzce:grass", new ArrayList<>());
        }
        refreshCardPool(!loaded || !hasSlotsField);
        maxSeedSlots = cardPoolConfig.maxSeedSlots;
        saveName = id == null ? "edited_level" : id.path();
    }

    /** Keeps the editor's card pool and "all slots" list in sync with registries and loaded JSON. */
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
            for (String slot : List.of("pvzce:pea_shooter", "pvzce:sunflower", "pvzce:potato_mine",
                    "pvzce:wall_nut", "pvzce:kernel_pult", "pvzce:cherry_bomb", "pvzce:chomper",
                    "pvzce:lily_pad", "pvzce:flower_pot", "pvzce:coffee_bean", "pvzce:marigold",
                    "pvzce:sun", "pvzce:shovel")) {
                if (available.contains(slot) || BuiltInRegistries.SLOT_TYPES.get(Identifier.tryParse(slot)) != null) {
                    cardPoolConfig.pool.add(slot);
                }
            }
        }
        maxSeedSlots = Math.max(0, cardPoolConfig.maxSeedSlots);
    }

    @Override
    protected void init() {
        int guiW = client.guiWidth();
        int guiH = client.guiHeight();
        int margin = 8;
        panelX = margin;
        panelW = Math.max(84, guiW / 5);
        itemsX = panelX + panelW + 6;
        itemsW = Math.max(80, guiW / 5);
        canvasX = itemsX + itemsW + 6;
        canvasW = Math.max(120, guiW - canvasX - margin);
        canvasY = 56;
        canvasH = Math.max(100, guiH - canvasY - 6);

        int top = guiH - 6;
        String[] categories = {"scene", "plant", "zombie", "rule"};
        // Clamped so four buttons plus their gaps always fit between the top of the
        // window and the resize buttons at y=86..116; the raw budget used to let the
        // fourth button overlap them on a short window.
        int categoryBudget = Math.max(4 * 22 + 3 * 3, top - 128);
        int categoryHeight = Math.min(48, Math.max(22, categoryBudget / categories.length));
        for (int i = 0; i < categories.length; i++) {
            String c = categories[i];
            addWidget(new Button(panelX, top - (i + 1) * categoryHeight - i * 3,
                    panelW, categoryHeight, categoryLabel(c), () -> setCategory(c)));
        }

        addWidget(new Button(panelX, 4, panelW, 42, "白天/夜晚规则",
                () -> selectedId = "pvzce:day".equals(selectedId) ? "pvzce:night" : "pvzce:day"));
        int smallW = (panelW - 6) / 2;
        addWidget(new Button(panelX, 52, smallW, 30, "宽-", () -> resize(width - 1, height)));
        addWidget(new Button(panelX + smallW + 4, 52, smallW, 30, "宽+", () -> resize(width + 1, height)));
        addWidget(new Button(panelX, 86, smallW, 30, "高-", () -> resize(width, height - 1)));
        addWidget(new Button(panelX + smallW + 4, 86, smallW, 30, "高+", () -> resize(width, height + 1)));

        itemList = new AbstractSelectionList<>(itemsX, canvasY, itemsW, canvasH, 34,
                (client, item, x, y) -> client.font().draw(shortId(item), x, y + 8, 0.8F, 1, 1, 1, 1));
        addWidget(itemList);
        refreshItems();

        int bw = Math.max(34, Math.min(56, Math.max(34, (canvasW - 70 - 24) / 6)));
        int nameWidth = Math.max(60, canvasW - (bw + 4) * 6 - 4);
        EditBox nameBox = new EditBox(canvasX, 8, nameWidth, 40, this::save);
        nameBox.setValue(saveName);
        addWidget(nameBox);
        int buttonX = canvasX + nameWidth + 4;
        addWidget(new Button(buttonX, 8, bw, 40, "卡池", this::openCardPoolDialog));
        buttonX += bw + 4;
        addWidget(new Button(buttonX, 8, bw, 40, "音乐", this::openMusicDialog));
        buttonX += bw + 4;
        addWidget(new Button(buttonX, 8, bw, 40, "波次", this::openWaveDialog));
        buttonX += bw + 4;
        addWidget(new Button(buttonX, 8, bw, 40, "保存", this::save));
        buttonX += bw + 4;
        addWidget(new Button(buttonX, 8, bw, 40, "测试", this::test));
        buttonX += bw + 4;
        addWidget(new Button(buttonX, 8, bw, 40, "返回", client::closeScreen));

        int dialogWidth = Math.min(760, guiW - 8);
        int dialogHeight = Math.min(520, guiH - 8);
        waveDialog = new WaveEditorDialog(client, (guiW - dialogWidth) / 2, (guiH - dialogHeight) / 2,
                dialogWidth, dialogHeight, waveConfig, () -> {
                });
        waveDialog.setVisible(false);
        addWidget(waveDialog);
        musicDialog = new MusicEditorDialog(client, (guiW - dialogWidth) / 2, (guiH - dialogHeight) / 2,
                dialogWidth, dialogHeight, musicConfig, () -> {
                });
        musicDialog.setVisible(false);
        addWidget(musicDialog);
        cardPoolDialog = new CardPoolEditorDialog(client, (guiW - dialogWidth) / 2, (guiH - dialogHeight) / 2,
                dialogWidth, Math.min(440, guiH - 8), cardPoolConfig, () -> {
                });
        cardPoolDialog.setVisible(false);
        addWidget(cardPoolDialog);
    }

    private void openCardPoolDialog() {
        if (cardPoolDialog != null) {
            cardPoolDialog.open();
        }
    }

    private void openMusicDialog() {
        if (musicDialog != null) {
            musicDialog.open();
        }
    }

    private void openWaveDialog() {
        if (waveDialog != null) {
            waveDialog.open();
        }
    }

    private String categoryLabel(String category) {
        return switch (category) {
            case "scene" -> "场景元素";
            case "plant" -> "植物";
            case "zombie" -> "僵尸";
            case "rule" -> "规则";
            default -> category;
        };
    }

    private void setCategory(String category) {
        this.category = category;
        if (!"rule".equals(category)) {
            refreshItems();
        }
    }

    private void refreshItems() {
        Set<Identifier> ids = switch (category) {
            case "plant" -> BuiltInRegistries.PLANTS.keySet();
            case "zombie" -> BuiltInRegistries.ZOMBIES.keySet();
            default -> BuiltInRegistries.SCENE_ELEMENTS.keySet();
        };
        List<String> items = new ArrayList<>(ids.stream().map(Identifier::toString).sorted().toList());
        itemList.setEntries(items);
        if (!items.isEmpty()) {
            selectedId = items.get(0);
            itemList.select(0);
        }
    }

    private void resize(int newWidth, int newHeight) {
        width = Math.max(1, Math.min(20, newWidth));
        height = Math.max(1, Math.min(10, newHeight));
    }

    private void placeAt(int cellX, int cellY) {
        if (cellX < 0 || cellX >= width || cellY < 0 || cellY >= height) {
            return;
        }
        String pos = cellX + "," + cellY;
        if ("scene".equals(category)) {
            removePositionFromAll(pos);
            scene.computeIfAbsent(selectedId, ignored -> new ArrayList<>()).add(pos);
        } else if ("plant".equals(category) || "zombie".equals(category)) {
            initialEntities.removeIf(entity -> entity.get("x").getAsInt() == cellX && entity.get("y").getAsInt() == cellY);
            JsonObject entity = new JsonObject();
            entity.addProperty("kind", category);
            entity.addProperty("id", selectedId);
            entity.addProperty("x", cellX);
            entity.addProperty("y", cellY);
            initialEntities.add(entity);
        } else if ("rule".equals(category)) {
            applyRulePreset(selectedId);
        }
    }

    private void removePositionFromAll(String pos) {
        for (List<String> positions : scene.values()) {
            positions.remove(pos);
        }
    }

    private void applyRulePreset(String ruleId) {
        switch (ruleId) {
            case "pvzce:day" -> {
                rules.addProperty("pvzce:day_length", 0);
                rules.addProperty("pvzce:night_length", -1);
            }
            case "pvzce:night" -> {
                rules.addProperty("pvzce:day_length", 600);
                rules.addProperty("pvzce:night_length", 600);
            }
            default -> {
            }
        }
    }

    private void removeAt(int cellX, int cellY) {
        String pos = cellX + "," + cellY;
        removePositionFromAll(pos);
        initialEntities.removeIf(entity -> entity.get("x").getAsInt() == cellX && entity.get("y").getAsInt() == cellY);
        scene.computeIfAbsent("pvzce:grass", ignored -> new ArrayList<>()).add(pos);
    }

    private JsonObject buildJson() {
        JsonObject root = new JsonObject();
        String safeName = sanitizeName(saveName == null ? "" : saveName);
        levelId = "pvzce:" + safeName;
        root.addProperty("id", levelId);
        root.addProperty("name", safeName);
        root.addProperty("description", "由关卡编辑器创建");
        root.addProperty("width", width);
        root.addProperty("height", height);
        JsonObject sceneJson = new JsonObject();
        for (var entry : scene.entrySet()) {
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
        root.add("rules", rules);
        JsonObject waveJson = waveConfig.toJson();
        root.addProperty("wave_interval_end_multiplier", waveConfig.intervalEndMultiplier);
        root.add("waves", waveJson.getAsJsonArray("waves"));
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
        root.add("teams", teams);
        root.addProperty("win_team", "pvzce:plant_team");
        JsonArray slots = new JsonArray();
        for (String slot : cardPoolConfig.pool) {
            slots.add(slot);
        }
        root.add("slots", slots);
        maxSeedSlots = Math.max(0, cardPoolConfig.maxSeedSlots);
        root.addProperty("max_seed_slots", maxSeedSlots);
        JsonObject unlock = new JsonObject();
        unlock.addProperty("pvzce:sun", true);
        root.add("unlock_resources", unlock);
        root.addProperty("initial_sun", 150);
        root.add("music", musicConfig.toJson());
        return root;
    }

    private void save() {
        saveName = levelNameFromBox();
        String safeName = sanitizeName(saveName);
        JsonObject root = buildJson();
        Path pack = client.gameDir().resolve("datapacks/user_levels");
        Path levelFile = pack.resolve("data/pvzce/pvzce/levels").resolve(safeName + ".json");
        try {
            Files.createDirectories(levelFile.getParent());
            Files.writeString(levelFile, root.toString());
            Path mcmeta = pack.resolve("pack.mcmeta");
            if (!Files.exists(mcmeta)) {
                Files.writeString(mcmeta, "{\"pack\":{\"pack_format\":1,\"description\":\"User levels\"}}");
            }
            client.connection().send(new CommandC2S("/reload"));
            client.level().addMessage("已保存并重载关卡 " + safeName);
        } catch (IOException e) {
            client.level().addMessage("保存失败: " + e.getMessage());
        }
    }

    private void test() {
        save();
        client.connection().send(new CommandC2S("/reload"));
        client.requestLevel(levelId, false);
    }

    private String levelNameFromBox() {
        for (var widget : widgets) {
            if (widget instanceof EditBox box) {
                return box.value().isBlank() ? "edited_level" : box.value().trim();
            }
        }
        return "edited_level";
    }

    private static String sanitizeName(String raw) {
        String safe = raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        if (safe.isBlank()) {
            safe = "edited_level";
        }
        return safe;
    }

    @Override
    public void mouseClicked(double mouseX, double mouseY, int button) {
        double guiX = client.guiMouseX(mouseX);
        double guiY = client.guiMouseY(mouseY);
        if (waveDialog != null && waveDialog.isVisible()) {
            waveDialog.mouseClicked(guiX, guiY, button);
            return;
        }
        if (musicDialog != null && musicDialog.isVisible()) {
            musicDialog.mouseClicked(guiX, guiY, button);
            return;
        }
        if (cardPoolDialog != null && cardPoolDialog.isVisible()) {
            cardPoolDialog.mouseClicked(guiX, guiY, button);
            return;
        }
        if (guiX >= canvasX && guiX < canvasX + canvasW && guiY >= canvasY && guiY < canvasY + canvasH) {
            if (!"rule".equals(category)) {
                int cellX = (int) ((guiX - canvasX) / cellSize());
                int cellY = (int) Math.floor((guiY - canvasY) / cellSize());
                if (button == 0) {
                    placeAt(cellX, cellY);
                } else if (button == 1) {
                    removeAt(cellX, cellY);
                }
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void tick() {
        if (waveDialog != null && waveDialog.isVisible()) {
            waveDialog.tick();
        }
        if (musicDialog != null && musicDialog.isVisible()) {
            musicDialog.tick();
        }
        if (cardPoolDialog != null && cardPoolDialog.isVisible()) {
            cardPoolDialog.tick();
        }
        if (itemList != null && itemList.selected() != null && !"rule".equals(category)) {
            selectedId = itemList.selected();
        }
    }

    @Override
    public void keyPressed(int key) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (waveDialog != null && waveDialog.isVisible()) {
                waveDialog.keyPressed(key);
            } else if (musicDialog != null && musicDialog.isVisible()) {
                musicDialog.keyPressed(key);
            } else if (cardPoolDialog != null && cardPoolDialog.isVisible()) {
                cardPoolDialog.keyPressed(key);
            } else {
                client.closeScreen();
            }
            return;
        }
        if (waveDialog != null && waveDialog.isVisible()) {
            waveDialog.keyPressed(key);
            return;
        }
        if (musicDialog != null && musicDialog.isVisible()) {
            musicDialog.keyPressed(key);
            return;
        }
        if (cardPoolDialog != null && cardPoolDialog.isVisible()) {
            cardPoolDialog.keyPressed(key);
            return;
        }
        super.keyPressed(key);
    }

    @Override
    public void charTyped(char codepoint) {
        if (waveDialog != null && waveDialog.isVisible()) {
            waveDialog.charTyped(codepoint);
            return;
        }
        if (musicDialog != null && musicDialog.isVisible()) {
            musicDialog.charTyped(codepoint);
            return;
        }
        if (cardPoolDialog != null && cardPoolDialog.isVisible()) {
            cardPoolDialog.charTyped(codepoint);
            return;
        }
        super.charTyped(codepoint);
    }

    @Override
    public boolean hasTextInputFocused() {
        if (waveDialog != null && waveDialog.isVisible()) {
            for (var child : waveDialog.children()) {
                if (child instanceof EditBox box && box.isFocused()) {
                    return true;
                }
            }
        }
        if (musicDialog != null && musicDialog.isVisible()) {
            for (var child : musicDialog.children()) {
                if (child instanceof EditBox box && box.isFocused()) {
                    return true;
                }
            }
        }
        if (cardPoolDialog != null && cardPoolDialog.isVisible()) {
            for (var child : cardPoolDialog.children()) {
                if (child instanceof EditBox box && box.isFocused()) {
                    return true;
                }
            }
        }
        return super.hasTextInputFocused();
    }

    @Override
    public void render() {
        client.beginGuiView();
        renderBackground(0.08F, 0.1F, 0.12F);
        for (var widget : widgets) {
            if (widget != waveDialog && widget != musicDialog && widget != cardPoolDialog) {
                widget.render(client);
            }
        }
        if ("rule".equals(category)) {
            int y = canvasY + Math.min(canvasH, 120);
            client.font().draw("规则预设", itemsX + 4, y, 0.8F, 1, 1, 1, 1);
            client.font().draw("点白天/夜晚后", itemsX + 4, y - 22, 0.65F, 0.85F, 0.85F, 0.85F, 1F);
            client.font().draw("再点画布生效", itemsX + 4, y - 42, 0.65F, 0.85F, 0.85F, 0.85F, 1F);
        }
        renderCanvas();
        String info = "编辑器: " + levelId + " " + width + "x" + height
                + " 当前: " + shortId(selectedId) + " 波次: " + waveConfig.waves.size()
                + " 卡池: " + cardPoolConfig.pool.size() + "/" + Math.max(0, cardPoolConfig.maxSeedSlots);
        client.font().draw(info, canvasX + 2, canvasY - 16, 0.7F, 1, 1, 1, 1);
        if (waveDialog != null) {
            waveDialog.render(client);
        }
        if (musicDialog != null) {
            musicDialog.render(client);
        }
        if (cardPoolDialog != null) {
            cardPoolDialog.render(client);
        }
    }

    private void renderCanvas() {
        client.drawSolid(canvasX - 2, canvasY - 2, canvasW + 4, canvasH + 4, 0, 0.15F, 0.15F, 0.15F, 1F);
        float size = cellSize();
        if (size <= 0F) {
            return;
        }
        // The editor canvas has its own scale, so route the shared tile renderer
        // through a temporary viewport-shaped draw by rendering to a zero-based
        // world projection is not possible here; instead render the 6x6 tiles
        // scaled to the canvas size directly.
        for (int blockY = 0; blockY < ceilDiv(height, SceneTileRenderer.TILE_CELLS); blockY++) {
            for (int blockX = 0; blockX < ceilDiv(width, SceneTileRenderer.TILE_CELLS); blockX++) {
                renderCanvasBlock(blockX, blockY, size);
            }
        }
        for (JsonObject entity : initialEntities) {
            int x = entity.get("x").getAsInt();
            int y = entity.get("y").getAsInt();
            String id = entity.get("id").getAsString();
            client.drawTexture(com.pvzce.client.renderer.EntityTextures.forEntity(id),
                    canvasX + x * size + size * 0.15F, canvasY + y * size + size * 0.15F,
                    size * 0.7F, size * 0.7F, 0.1F, 1, 1, 1, 1);
        }
    }

    /** One 6x6 scene block drawn full when uniform, cell-by-cell when mixed. */
    private void renderCanvasBlock(int blockX, int blockY, float size) {
        int x0 = blockX * SceneTileRenderer.TILE_CELLS;
        int y0 = blockY * SceneTileRenderer.TILE_CELLS;
        int x1 = Math.min(width, x0 + SceneTileRenderer.TILE_CELLS);
        int y1 = Math.min(height, y0 + SceneTileRenderer.TILE_CELLS);
        if (x1 <= x0 || y1 <= y0) {
            return;
        }
        String first = paintSceneId(x0, y0);
        boolean uniform = true;
        for (int y = y0; y < y1 && uniform; y++) {
            for (int x = x0; x < x1; x++) {
                if (!paintSceneId(x, y).equals(first)) {
                    uniform = false;
                    break;
                }
            }
        }

        float blockWorld = SceneTileRenderer.TILE_CELLS;
        float baseX = canvasX + x0 * size;
        float baseY = canvasY + y0 * size;
        if (uniform && ("pvzce:grass".equals(first) || "pvzce:ground".equals(first))) {
            float cols = x1 - x0;
            float rows = y1 - y0;
            client.drawTextureRegion(textureForScene(first), 0F, 0F,
                    cols / blockWorld, rows / blockWorld,
                    baseX, baseY, cols * size, rows * size, 0F, 1, 1, 1, 1);
            return;
        }
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                float drawX = canvasX + x * size;
                float drawY = canvasY + y * size;
                String sceneId = sceneAt(x, y);
                if (sceneId == null) {
                    // Unpainted: the game draws the level background through it, so the
                    // canvas has to show the same thing or the author cannot see the
                    // holes they are about to save.
                    drawUnpaintedCell(drawX, drawY, size);
                    continue;
                }
                if ("pvzce:grass".equals(sceneId) || "pvzce:ground".equals(sceneId)) {
                    int tx = Math.floorMod(x, SceneTileRenderer.TILE_CELLS);
                    int ty = Math.floorMod(y, SceneTileRenderer.TILE_CELLS);
                    float step = 1F / SceneTileRenderer.TILE_CELLS;
                    client.drawTextureRegion(textureForScene(sceneId),
                            tx * step, ty * step, (tx + 1) * step, (ty + 1) * step,
                            drawX, drawY, size, size, 0.05F, 1, 1, 1, 1);
                } else {
                    client.drawTexture(textureForScene(sceneId), drawX, drawY, size, size, 0.05F, 1, 1, 1, 1);
                }
            }
        }
    }

    private static Identifier textureForScene(String sceneId) {
        return SceneTileRenderer.sceneTexture(sceneId);
    }

    private static int ceilDiv(int value, int divisor) {
        return com.pvzce.common.util.MathUtil.ceilDiv(value, divisor);
    }

    private float cellSize() {
        return Math.min(canvasW / (float) width, canvasH / (float) height);
    }

    /**
     * The element at a cell, or null when the author has not painted it.
     *
     * <p>This used to fall back to {@code pvzce:grass}, which made the editor show a
     * complete board while the saved level had holes in it: the game draws the level
     * background through an unpainted cell and the server refuses every plant there,
     * so a board that looked finished on this canvas came out with bare dirt patches
     * in play. Reporting the truth lets the canvas mark the gaps instead of hiding
     * them. The caller passes the fallback it wants to DRAW with.
     */
    private String sceneAt(int x, int y) {
        String pos = x + "," + y;
        for (var entry : scene.entrySet()) {
            if (entry.getValue().contains(pos)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** Texture used to make an unpainted cell visible; see {@link #sceneAt}. */
    private static final String UNPAINTED = "pvzce:grass";

    /** Scene id for the uniform-block path, which cannot compare nulls. */
    private String paintSceneId(int x, int y) {
        String id = sceneAt(x, y);
        return id == null ? UNPAINTED : id;
    }

    /**
     * Marks a cell the author has not painted.
     *
     * <p>Drawn as the grass texture at low alpha rather than as a flat colour, so it
     * reads as "empty, will become bare dirt in game" next to the real grass tiles
     * instead of as a fifth terrain type.
     */
    private void drawUnpaintedCell(float drawX, float drawY, float size) {
        client.drawTexture(textureForScene(UNPAINTED), drawX, drawY, size, size,
                0.04F, 0.45F, 0.45F, 0.45F, 0.55F);
    }

    private static String shortId(String id) {
        return com.pvzce.client.gui.GuiText.shortId(id);
    }
}
