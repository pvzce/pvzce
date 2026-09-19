package com.pvzce.client.gui.editor.pages;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.PaletteList;
import com.pvzce.client.gui.editor.EditorContext;
import com.pvzce.client.gui.editor.EditorPage;
import com.pvzce.client.gui.editor.LevelFileWriter;
import com.pvzce.client.renderer.EntityTextures;
import com.pvzce.client.renderer.EntityVisuals;
import com.pvzce.client.renderer.LevelStage;
import com.pvzce.client.renderer.SceneTileRenderer;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.JsonDraft;
import com.pvzce.common.util.MathUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The board tabs: terrain, plants and zombies are three views of one board.
 *
 * <p>They share a {@link Model} because the level file has one {@code scene} block and one
 * {@code initial_entities} block. A cell painted on the terrain tab and a plant placed on the
 * plant tab are the same board, and a save writes both blocks once. The editor creates the
 * model once and hands it to {@link #terrain}, {@link #plant} and {@link #zombie}; a page owns
 * only what is its own - its palette, its selection and its widgets.
 *
 * <p>Everything the screen did for these three tabs moved with them: the palette, the size and
 * 清空实体 buttons, the board rendering, the click-to-paint / click-to-place rules and the
 * canvas block of the file. The DELETE key that removes the entity under the cursor is still
 * handled by {@code EditorScreen}, because {@link EditorPage} has no key hook.
 */
public final class CanvasPage implements EditorPage {
    private static final int MAX_COLUMNS = 20;
    private static final int MAX_ROWS = 10;

    /**
     * The board the three views share: its size, the cells the author painted and the presets
     * they placed.
     *
     * <p>One instance per level. It is the model the pages read the level file into and write
     * back, plus the two things every view needs from the board itself: a live preview entity
     * per preset, and the release of their playbacks.
     */
    public static final class Model {
        /** Board width in cells. */
        public int width;
        /** Board height in cells. */
        public int height;
        /** Painted terrain: scene element id to the cells it covers, each written "x,y". */
        public final Map<String, List<String>> scene = new LinkedHashMap<>();
        /** The level's presets, kept in the level file's own shape. */
        public final List<JsonObject> initialEntities = new ArrayList<>();
        /** One preview per preset; nothing outside this file draws them. */
        private final List<CanvasEntity> canvasEntities = new ArrayList<>();

        public Model(int width, int height) {
            this.width = MathUtil.clamp(width, 1, MAX_COLUMNS);
            this.height = MathUtil.clamp(height, 1, MAX_ROWS);
        }

        /**
         * Drops the preview playbacks.
         *
         * <p>The editor calls this when it closes or hands the level to the game. Without it
         * every board edit left strongly referenced entities in the animation manager,
         * advanced on every frame for the rest of the session - the same leak the seed
         * chooser's preview had to fix.
         */
        public void releaseCanvasAnimations(EditorContext context) {
            if (context.client().animations() == null) {
                return;
            }
            for (CanvasEntity entry : canvasEntities) {
                context.client().animations().release(entry.entity);
            }
        }

        /** Rebuilds the previews from the presets; every board edit goes through here. */
        private void syncCanvasEntities(EditorContext context) {
            releaseCanvasAnimations(context);
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
                if (context.client().animations() != null) {
                    entity.attachAnimationManager(context.client().animations());
                }
                canvasEntities.add(new CanvasEntity(json, entity));
            }
        }

        private void resize(EditorContext context, int newWidth, int newHeight) {
            int clampedW = MathUtil.clamp(newWidth, 1, MAX_COLUMNS);
            int clampedH = MathUtil.clamp(newHeight, 1, MAX_ROWS);
            if (clampedW == width && clampedH == height) {
                return;
            }
            width = clampedW;
            height = clampedH;
            // Preset entities outside the new bounds would be invisible and unfixable.
            initialEntities.removeIf(entity -> entity.get("x").getAsInt() >= width
                    || entity.get("y").getAsInt() >= height);
            syncCanvasEntities(context);
        }

        private void clearEntities(EditorContext context) {
            releaseCanvasAnimations(context);
            initialEntities.clear();
            syncCanvasEntities(context);
        }

        /** The element at a cell, or {@code null} when the author has not painted it. */
        /**
         * The element this cell is painted with, or {@code null} when the file leaves it bare.
         *
         * <p>The rule - a cell declared twice takes the later declaration - lives in
         * {@link com.pvzce.common.core.SceneCells#lookup}: it is what playing the level does, and
         * having the canvas answer it differently meant a file that paints four cells water over a
         * grass base showed grass here and water in the game.
         */
        private String sceneAt(int x, int y) {
            return com.pvzce.common.core.SceneCells.lookup(scene, x, y);
        }

        private void removePositionFromAll(String pos) {
            for (List<String> positions : scene.values()) {
                positions.remove(pos);
            }
        }
    }

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

    /** Which of the three board views a page is: its tab, its palette and its click rule. */
    private enum View {
        TERRAIN("terrain", 10, PaletteList.Kind.SCENE),
        PLANT("plant", 11, PaletteList.Kind.ENTITY),
        ZOMBIE("zombie", 12, PaletteList.Kind.ENTITY);

        private final String key;
        private final int order;
        private final PaletteList.Kind paletteKind;

        View(String key, int order, PaletteList.Kind paletteKind) {
            this.key = key;
            this.order = order;
            this.paletteKind = paletteKind;
        }

        public String key() {
            return key;
        }

        public int order() {
            return order;
        }

        public PaletteList.Kind paletteKind() {
            return paletteKind;
        }
    }

    private final Model model;
    private final View view;
    private PaletteList palette;
    /** The cell the terrain view paints with; the scene palette is what changes it. */
    private String selectedSceneId = "pvzce:grass";
    /** The entity the plant and zombie views place; the entity palettes change it. */
    private String selectedEntityId = "";

    private CanvasPage(Model model, View view) {
        this.model = model;
        this.view = view;
    }

    /** The terrain tab. All three factories must be handed the <em>same</em> model. */
    public static CanvasPage terrain(Model model) {
        return new CanvasPage(model, View.TERRAIN);
    }

    /** The plant tab: the board's plants, on the same model as the other two views. */
    public static CanvasPage plant(Model model) {
        return new CanvasPage(model, View.PLANT);
    }

    /** The zombie tab: the board's zombies, on the same model as the other two views. */
    public static CanvasPage zombie(Model model) {
        return new CanvasPage(model, View.ZOMBIE);
    }

    @Override
    public String id() {
        return view.key();
    }

    @Override
    public String label() {
        return GuiLang.raw("pvzce.editor.page." + id(), id());
    }

    @Override
    public int order() {
        return view.order();
    }

    @Override
    public boolean hasPalette() {
        return true;
    }

    /**
     * Loads the board from the level JSON.
     *
     * <p>Runs for every page of the editor, so all three views read the block into the same
     * model. It replaces the board rather than merging into it: a second read must not append a
     * second copy of every painted cell.
     */
    @Override
    public void readFrom(EditorContext context) {
        JsonDraft draft = context.draft();
        model.width = MathUtil.clamp(draft.getInt("width", model.width), 1, MAX_COLUMNS);
        model.height = MathUtil.clamp(draft.getInt("height", model.height), 1, MAX_ROWS);
        model.scene.clear();
        draft.get("scene").filter(JsonElement::isJsonObject).ifPresent(element -> {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                List<String> positions = new ArrayList<>();
                for (JsonElement position : entry.getValue().getAsJsonArray()) {
                    positions.add(position.getAsString());
                }
                model.scene.put(entry.getKey(), positions);
            }
        });
        model.initialEntities.clear();
        for (JsonElement element : draft.getArray("initial_entities")) {
            model.initialEntities.add(element.getAsJsonObject().deepCopy());
        }
        model.scene.putIfAbsent("pvzce:grass", new ArrayList<>());
        model.syncCanvasEntities(context);
    }

    /**
     * Writes the board's two blocks.
     *
     * <p>Both are a function of the shared model, so each view writes the same bytes for them
     * and the file does not depend on which tab was on screen.
     */
    @Override
    public void writeTo(EditorContext context) {
        LevelFileWriter.canvas(context.draft(), model.width, model.height, model.scene,
                model.initialEntities);
    }

    @Override
    public void build(EditorContext context) {
        buildPalette(context);
        buildCanvasSidePanel(context);
    }

    @Override
    public void render(EditorContext context) {
        renderBoard(context);
        // The tab's own hint, under the side panel's buttons; it was the canvas arm of the
        // screen's page-label switch.
        EditorContext.Rect side = context.side();
        context.client().font().draw(view == View.TERRAIN
                        ? GuiLang.raw("pvzce.editor.tip.terrain", "左键涂格，右键恢复草地")
                        : GuiLang.raw("pvzce.editor.tip.entity", "左键放置，右键移除"),
                side.x() + 4, side.y() + side.height() - 10F, 0.7F, 0.85F, 0.9F, 0.9F, 1F);
    }

    /** The palette's selection is what the next click paints or places. */
    @Override
    public void tick(EditorContext context) {
        if (palette != null) {
            selectFromPalette(palette.selected());
        }
    }

    @Override
    public boolean onMouseClicked(EditorContext context, double guiX, double guiY, int button) {
        int[] cell = cellAtCursor(context);
        if (cell == null) {
            return false;
        }
        if (button == 0) {
            placeAt(context, cell[0], cell[1]);
        } else if (button == 1) {
            removeAt(context, cell[0], cell[1]);
        }
        return true;
    }

    private void buildPalette(EditorContext context) {
        EditorContext.Rect area = context.palette();
        int rowH = MathUtil.clamp(context.content().height() / 10, 32, 48);
        palette = context.own(new PaletteList(area.x(), area.y(), area.width(), area.height(),
                rowH, view.paletteKind()));
        List<PaletteList.Item> items = switch (view.paletteKind()) {
            // Grass last, i.e. at the bottom row: the list is drawn bottom-up, and the
            // bottom row is the one nearest the board an author is painting.
            case SCENE -> java.util.stream.Stream.concat(
                            BuiltInRegistries.SCENE_ELEMENTS.keySet().stream()
                                    .sorted()
                                    .filter(id -> !"grass".equals(id.path())),
                            java.util.stream.Stream.of(Identifier.withDefaultNamespace("grass")))
                    .map(id -> PaletteList.Item.of(context.client(), PaletteList.Kind.SCENE, id, null))
                    .toList();
            case ENTITY -> (view == View.ZOMBIE
                    ? BuiltInRegistries.ZOMBIES.keySet()
                    : BuiltInRegistries.PLANTS.keySet()).stream()
                    .sorted()
                    .map(id -> PaletteList.Item.of(context.client(), PaletteList.Kind.ENTITY, id, null))
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

    private void buildCanvasSidePanel(EditorContext context) {
        EditorContext.Rect side = context.side();
        int rowH = MathUtil.clamp(context.content().height() / 14, 26, 34);
        int buttonW = Math.max(56, (side.width() - 8) / 2);
        int top = side.y() + side.height() - rowH;
        context.own(new Button(side.x(), top, buttonW, rowH, "宽 -",
                () -> model.resize(context, model.width - 1, model.height)));
        context.own(new Button(side.x() + buttonW + 8, top, buttonW, rowH, "宽 +",
                () -> model.resize(context, model.width + 1, model.height)));
        context.own(new Button(side.x(), top - rowH - 6, buttonW, rowH, "高 -",
                () -> model.resize(context, model.width, model.height - 1)));
        context.own(new Button(side.x() + buttonW + 8, top - rowH - 6, buttonW, rowH, "高 +",
                () -> model.resize(context, model.width, model.height + 1)));
        context.own(new Button(side.x(), top - (rowH + 6) * 2, side.width(), rowH,
                GuiLang.raw("pvzce.editor.clear_entities", "清空实体"),
                () -> model.clearEntities(context)));
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
    private void renderBoard(EditorContext context) {
        PvzceClient client = context.client();
        EditorContext.Rect content = context.content();
        LevelStage.Board board = board(context);
        float boardX = content.x() + board.x();
        float boardY = content.y() + board.y();
        float cellH = Math.max(0.0001F, board.cellHeight());

        client.clipping().push(boardX, boardY, board.width(), board.height());
        try {
            client.beginOverlayWorldView(boardX, boardY, board.width(), board.height(),
                    0F, boardColumns(), 0F, boardRows());
            SceneTileRenderer.render(client, boardColumns(), boardRows(),
                    (x, y) -> {
                        String id = model.sceneAt(x, y);
                        return id == null ? "pvzce:grass" : id;
                    }, 1F, 0F);
            for (CanvasEntity entry : model.canvasEntities) {
                renderCanvasEntity(context, entry, cellH);
            }
            // Unpainted cells are marked after the tiles so the author can see the
            // holes a level JSON would really have there.
            for (int y = 0; y < boardRows(); y++) {
                for (int x = 0; x < boardColumns(); x++) {
                    if (model.sceneAt(x, y) == null) {
                        client.drawSolid(x, y, 1F, 1F, 6F, 0.55F, 0.55F, 0.55F, 0.35F);
                    }
                }
            }
            int[] hover = cellAtCursor(context);
            if (hover != null) {
                client.drawSolid(hover[0], hover[1], 1F, 1F, 7F, 1F, 1F, 0.2F, 0.35F);
            }
            client.beginGuiView();
        } finally {
            client.clipping().pop();
        }
    }

    private void renderCanvasEntity(EditorContext context, CanvasEntity entry, float cellHeight) {
        PvzceClient client = context.client();
        String sceneId = model.sceneAt(entry.x(), entry.y());
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

    /**
     * The board footprint inside the canvas, in GUI pixels.
     *
     * <p>{@link LevelStage#board} builds a board at the scale it is <em>played</em> at, which is
     * wider than the editor's canvas panel once the side panel and the palette have taken their
     * share of a 1280x720 window: a nine-column level came out about 590px wide inside a 400px
     * panel, so the author saw the last few columns and - the canvas has no panning - could not
     * reach the ones off the edge. The whole board is scaled down uniformly when it does not fit,
     * which keeps every cell's aspect ratio (so a plant still looks the size it will in play) and
     * makes {@link #cellAtCursor} the exact inverse of what is drawn, because both ask here.
     */
    private LevelStage.Board board(EditorContext context) {
        EditorContext.Rect content = context.content();
        LevelStage.Board natural = LevelStage.board(Math.max(1, content.width()),
                Math.max(1, content.height()), boardColumns(), boardRows());
        float fit = Math.min(1F, Math.min(
                content.width() / Math.max(1F, natural.width()),
                content.height() / Math.max(1F, natural.height())));
        if (fit >= 1F) {
            return natural;
        }
        float width = natural.width() * fit;
        float height = natural.height() * fit;
        return new LevelStage.Board(
                // Centred in the panel: the play-scale board is anchored to the house side, and
                // keeping that anchor after scaling would leave the gap on one side.
                (content.width() - width) / 2F,
                (content.height() - height) / 2F,
                width, height,
                natural.cellWidth() * fit, natural.cellHeight() * fit, natural.fit() * fit);
    }

    private int boardRows() {
        return Math.max(1, model.height);
    }

    private int boardColumns() {
        return Math.max(1, model.width);
    }

    /**
     * The board cell under the cursor, or {@code null} outside the board rectangle.
     *
     * <p>Mirrors {@link #renderBoard}: the same footprint, the same clipping rect, the
     * same cell size. The old editor divided the canvas by {@code cellSize()} while a
     * uniform 6x6 tile pass drew with a different margin, so the outermost cells were
     * systematically offset from their highlight.
     */
    private int[] cellAtCursor(EditorContext context) {
        PvzceClient client = context.client();
        EditorContext.Rect content = context.content();
        LevelStage.Board board = board(context);
        float boardX = content.x() + board.x();
        float boardY = content.y() + board.y();
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

    /**
     * Places a preset without a click, for the screenshot smoke hook.
     *
     * <p>Selects the preset the way the palette does and then places it, so what the smoke run
     * photographs went through exactly the path a click goes through.
     */
    public void placePreset(EditorContext context, String entityId, int cellX, int cellY) {
        selectedEntityId = entityId;
        placeAt(context, cellX, cellY);
    }

    private void placeAt(EditorContext context, int cellX, int cellY) {
        if (cellX < 0 || cellX >= model.width || cellY < 0 || cellY >= model.height) {
            return;
        }
        String pos = cellX + "," + cellY;
        switch (view) {
            case TERRAIN -> {
                model.removePositionFromAll(pos);
                model.scene.computeIfAbsent(selectedSceneId, ignored -> new ArrayList<>()).add(pos);
            }
            case PLANT, ZOMBIE -> {
                if (selectedEntityId.isEmpty()) {
                    return;
                }
                model.initialEntities.removeIf(entity -> entity.get("x").getAsInt() == cellX
                        && entity.get("y").getAsInt() == cellY);
                JsonObject entity = new JsonObject();
                entity.addProperty("kind", view == View.ZOMBIE ? "zombie" : "plant");
                entity.addProperty("id", selectedEntityId);
                entity.addProperty("x", cellX);
                entity.addProperty("y", cellY);
                model.initialEntities.add(entity);
                model.syncCanvasEntities(context);
            }
        }
    }

    private void removeAt(EditorContext context, int cellX, int cellY) {
        String pos = cellX + "," + cellY;
        boolean changed = model.initialEntities.removeIf(entity -> entity.get("x").getAsInt() == cellX
                && entity.get("y").getAsInt() == cellY);
        model.removePositionFromAll(pos);
        model.scene.computeIfAbsent("pvzce:grass", ignored -> new ArrayList<>()).add(pos);
        if (changed) {
            model.syncCanvasEntities(context);
        }
    }

}
