package com.pvzce.client.gui.screens;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.util.WorldPaths;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.AbstractWidget;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.editor.BuiltInEditorPages;
import com.pvzce.client.gui.editor.EditorContext;
import com.pvzce.client.gui.editor.EditorPage;
import com.pvzce.client.gui.editor.pages.CanvasPage;
import com.pvzce.common.core.JsonDraft;
import com.pvzce.common.network.packet.CommandC2S;
import com.pvzce.common.util.MathUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The level editor's shell: the file, the frame and the pages.
 *
 * <p>Every page is an {@link EditorPage} of its own (see
 * {@code com.pvzce.client.gui.editor.pages}); this class owns what belongs to the editor as a
 * whole - which level is open, where its file is, the working copy of its JSON, the navigation
 * bar, the action bar, and the save path. It used to own the pages too: a private
 * {@code enum Page}, three {@code switch} statements and every page's widgets, which is why
 * adding a page meant editing four places and why a page could not be added from outside this
 * repository.
 *
 * <p>The JSON is edited as a {@link JsonDraft}: pages write the fields they own, so a field no
 * page models - a newer version's block, another tool's metadata - survives a save by
 * construction rather than by a seventeen-parameter writer remembering to copy it across.
 */
public final class EditorScreen extends Screen {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("PVZCE/Editor");
    private static final int MAX_COLUMNS = 20;
    private static final int MAX_ROWS = 10;

    /**
     * The level's id, which is also its theme/category path.
     *
     * <p>Not final: the info page may move a level to another theme/category, and that is a
     * rename of the id. It is the only operation that changes one - the id field itself is
     * read-only, because saves and other levels refer to it.
     */
    private com.pvzce.common.workshop.Workspace workspace;
    private com.pvzce.api.registry.RegistryAccess workspaceCatalog;
    private Path previousAssetOverlay;
    private boolean workspaceAssetsAttached;
    private Identifier levelId;
    /** Where to write on save; {@code null} until resolved for a brand-new level. */
    private Path sourceFile;
    /** The JSON as loaded; the draft starts as a copy of it. */
    private JsonObject sourceJson = new JsonObject();
    /** The working copy every page writes its own fields into. */
    private JsonDraft draft = JsonDraft.empty();
    /** The name shown in the header; the info page owns it. */
    private String levelName;
    /** The board the three canvas tabs share; see {@link CanvasPage.Model}. */
    private CanvasPage.Model canvas;
    /** Size a brand-new level starts with; zero for a level opened from a file. */
    private final int newLevelWidth;
    private final int newLevelHeight;

    private List<EditorPage> pages = List.of();
    private List<EditorPage> allPages = List.of();
    private com.pvzce.client.gui.editor.EditorModules modules;
    private List<EditorPage> navPages=List.of();
    private int navPage;
    private EditorPage currentPage;
    private final EditorContext editorContext = new EditorContext(this);
    private final List<AbstractWidget> pageWidgets = new ArrayList<>();
    private List<Button> navButtons = new ArrayList<>();

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

    /** Opens an existing level: its JSON comes from the pack stack. */
    public EditorScreen(com.pvzce.client.PvzceClient client, Identifier levelId) {
        this(client, levelId, null, 0, 0);
    }

    /** Opens a brand-new level, which has no file yet and takes its size from the dialog. */
    public EditorScreen(com.pvzce.client.PvzceClient client, Identifier levelId, String name,
                        int width, int height) {
        super(client);
        this.levelId = levelId;
        this.levelName = name == null || name.isBlank() ? levelId.path() : name;
        this.newLevelWidth = width;
        this.newLevelHeight = height;
        loadExisting();
    }

    public EditorScreen(com.pvzce.client.PvzceClient client, com.pvzce.common.workshop.Workspace workspace,
                        Identifier id, Path source, JsonObject json) {
        super(client);
        this.workspace = workspace;
        this.workspaceCatalog = com.pvzce.common.workshop.WorkshopContent.catalog(workspace, false);
        this.levelId = id;
        this.levelName = json.has("name") ? json.get("name").getAsString() : id.path();
        this.sourceFile = source;
        this.sourceJson = json.deepCopy();
        this.newLevelWidth = 9;
        this.newLevelHeight = 5;
        loadExisting();
    }

    /** Reads the level's JSON, then builds the pages over it. */
    private void loadExisting() {
        LevelSource source = workspace == null ? findLevelSource() : new LevelSource(sourceJson, sourceFile);
        if (source != null) {
            sourceJson = source.json();
            sourceFile = source.writableFile();
        }
        if (!sourceJson.entrySet().isEmpty()) {
            if (sourceJson.has("name") && !sourceJson.get("name").getAsString().isBlank()) {
                levelName = sourceJson.get("name").getAsString();
            }
        }
        int width = MathUtil.clamp(sourceJson.has("width") ? sourceJson.get("width").getAsInt()
                : (newLevelWidth > 0 ? newLevelWidth : 9), 1, MAX_COLUMNS);
        int height = MathUtil.clamp(sourceJson.has("height") ? sourceJson.get("height").getAsInt()
                : (newLevelHeight > 0 ? newLevelHeight : 5), 1, MAX_ROWS);

        draft = JsonDraft.of(sourceJson);
        canvas = new CanvasPage.Model(width, height);
        modules=new com.pvzce.client.gui.editor.EditorModules(draft.json());
        allPages = BuiltInEditorPages.create(this, editorContext, canvas);
        for (EditorPage page : allPages) {
            page.readFrom(editorContext);
        }
        refreshEditorPages();
        currentPage = pages.isEmpty() ? null : pages.get(0);
    }

    public com.pvzce.client.gui.editor.EditorModules editorModules(){return modules;}
    public List<EditorPage> editorAllPages(){return List.copyOf(allPages);}
    public List<String> editorVisiblePages(){return pages.stream().map(EditorPage::id).toList();}
    private void refreshEditorPages(){
        pages=allPages.stream().filter(page->modules.shown(page)&&page.visibleFor(editorContext)).toList();
    }
    private void flushEditorPages(){for(EditorPage page:allPages)if(modules.active(page)&&page.visibleFor(editorContext))page.writeTo(editorContext);}
    public void openEditorModule(String id){
        modules.show(id);refreshEditorPages();
        onResize();showPage(id);
    }
    public void setEditorModuleShown(String id,boolean shown){
        flushEditorPages();
        if(shown)modules.show(id);else modules.hide(id);
        refreshEditorPages();
        if(currentPage==null||!pages.contains(currentPage))currentPage=pages.get(0);
        navPage=0;onResize();
    }
    public void addEditorModule(com.pvzce.client.gui.editor.EditorModules.Addition addition){
        flushEditorPages();modules.add(editorContext,addition);
        List<EditorPage> updated=new ArrayList<>(allPages);
        for(EditorPage candidate:com.pvzce.client.gui.editor.pages.MechanicPages.create(editorContext)){
            if(updated.stream().noneMatch(page->page.id().equals(candidate.id()))){candidate.readFrom(editorContext);updated.add(candidate);}
        }
        updated.sort(java.util.Comparator.comparingInt(EditorPage::order).thenComparing(EditorPage::id));allPages=List.copyOf(updated);
        refreshEditorPages();
        if(!pages.contains(currentPage))currentPage=pages.get(0);
        navPage=0;onResize();
        setStatus("已添加："+addition.label());
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
            LOGGER.error("Could not read level " + levelId + " for the editor", e);
        }
        return null;
    }



    // ------------------------------------------------------------------
    // Layout
    // ------------------------------------------------------------------

    @Override
    protected void init() {
        if (workspace != null && client.resources() != null) {
            if (!workspaceAssetsAttached) { previousAssetOverlay = client.resources().workspaceOverlay(); workspaceAssetsAttached = true; }
            if (!workspace.root().equals(client.resources().workspaceOverlay())) client.previewWorkspaceAssets(workspace.root());
        }
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
        // 22px are reserved above the panel for the page label; without it the label and the
        // pages' own labels were drawn into the nav bar.
        centerH = Math.max(80, navY - margin - centerY - 6 - 22);

        buildNav();
        buildActionBar();
        buildCurrentPage();
    }

    /**
     * Enabled pages share a strip, with arrows when the window cannot fit readable tabs.
     */
    private void buildNav() {
        int gap=4,count=Math.max(1,pages.size()),available=client.guiWidth()-20;
        boolean paged=count>Math.max(1,available/70);
        int capacity=navCapacity();
        navPage=Math.min(navPage,Math.max(0,(count-1)/capacity));
        navPages=pages.subList(navPage*capacity,Math.min(count,(navPage+1)*capacity));
        int start=paged?48:10,buttonW=Math.max(1,(available-(paged?76:0)-gap*(navPages.size()-1))/Math.max(1,navPages.size()));
        List<Button> buttons=new ArrayList<>();
        if(paged){
            addWidget(new Button(10,navY,32,navH,"←",()->{navPage=Math.max(0,navPage-1);onResize();}));
            addWidget(new Button(client.guiWidth()-42,navY,32,navH,"→",()->{if((navPage+1)*capacity<count)navPage++;onResize();}));
        }
        for(int i=0;i<navPages.size();i++){
            EditorPage target=navPages.get(i);
            buttons.add(new Button(start+i*(buttonW+gap),navY,buttonW,navH,target.label(),()->switchPage(target)));
        }
        navButtons = buttons;
        for (Button button : buttons) {
            addWidget(button);
        }
        refreshNavState();
    }

    private int navCapacity(){
        int available=client.guiWidth()-20,count=Math.max(1,pages.size());
        return count>Math.max(1,available/70)?Math.max(1,(available-76)/70):count;
    }

    private void refreshNavState() {
        for (int i = 0; i < navButtons.size() && i < navPages.size(); i++) {
            navButtons.get(i).setActive(navPages.get(i) != currentPage);
        }
    }

    /** Total width the action buttons occupy; the header starts after them. */
    private int actionButtonSpan;

    /**
     * Save, test and back - the level actions, and nothing else.
     *
     * <p>"编辑波次" and "音乐" used to sit here as shortcuts into a dialog and a second screen,
     * duplicating pages that already exist in the navigation bar. Two entries for one
     * destination is how the pages stayed half-built.
     */
    private void buildActionBar() {
        int w = Math.max(72, Math.min(120, client.guiWidth() / 10));
        actionButtonSpan = (w + 6) * (workspace == null ? 3 : 4);
        addWidget(new Button(paletteX, actionY, w, actionH, GuiLang.raw("pvzce.save", "保存"), this::save));
        addWidget(new Button(paletteX + w + 6, actionY, w, actionH, GuiLang.raw("pvzce.test", "测试"), this::test));
        addWidget(new Button(paletteX + (w + 6) * 2, actionY, w, actionH,
                GuiLang.raw("pvzce.back", "返回"), this::requestClose));
        if (workspace != null) addWidget(new Button(paletteX + (w + 6) * 3, actionY, w, actionH,
                GuiLang.raw("gui.pvzce.workshop.advanced_json", "高级 JSON"), () -> {
                    if (!saveToDisk()) return;
                    try {
                        Screen advanced = new com.pvzce.client.gui.workshop.WorkshopJsonScreen(client, workspace,
                                workspace.root().relativize(sourceFile).toString().replace('\\', '/'));
                        client.popScreen();
                        client.openScreen(advanced);
                    } catch (IOException e) { setStatus(e.getMessage()); }
                }));
    }

    /**
     * The rectangle the pages that need the whole width use: palette plus centre plus side
     * panel. The wave and music tables have two columns of their own and read badly squeezed
     * into the centre column.
     */
    private int[] fullContentArea() {
        return new int[]{paletteX, centerY, sideX + sideW - paletteX, centerH + 22};
    }

    /** Switches tabs: the outgoing page drops its widgets, the incoming one builds its own. */
    private void switchPage(EditorPage target) {
        if (target == null || currentPage == target) {
            return;
        }
        if (currentPage != null) {
            currentPage.onClosed(editorContext);
        }
        currentPage = target;
        for (AbstractWidget widget : pageWidgets) {
            widgets.remove(widget);
        }
        pageWidgets.clear();
        buildCurrentPage();
        refreshNavState();
    }

    private <T extends AbstractWidget> T own(T widget) {
        pageWidgets.add(widget);
        addWidget(widget);
        return widget;
    }

    /**
     * Rebuilds the page on screen.
     *
     * <p>Whatever the outgoing page had typed is flushed into the draft first: its widgets are
     * about to be thrown away and the draft is what survives - a window resize rebuilds the
     * page, and losing a half-typed wave to a resize would be invisible.
     */
    private void buildCurrentPage() {
        if (currentPage != null) {
            currentPage.writeTo(editorContext);
        }
        if (currentPage != null) {
            currentPage.build(editorContext);
        }
    }

    /** Puts a page on screen by id; used by the smoke hook and by "open page X". */
    private void showPage(String id) {
        for (EditorPage candidate : pages) {
            if (candidate.id().equals(id)) {
                switchPage(candidate);
                if(!navPages.contains(candidate)){
                    navPage=pages.indexOf(candidate)/navCapacity();onResize();
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // The seam the pages use (see EditorContext)
    // ------------------------------------------------------------------

    public JsonDraft draft() {
        return draft;
    }

    public Identifier editorLevelId() {
        return levelId;
    }

    public void setEditorLevelId(Identifier id) {
        this.levelId = id;
    }

    public Path editorPackRoot() { return workspace == null ? client.gameDir().resolve("datapacks/user_levels") : workspace.root(); }

    public com.pvzce.api.registry.RegistryAccess editorCatalog() {
        return workspaceCatalog == null ? com.pvzce.common.core.BuiltInRegistries.ACCESS : workspaceCatalog;
    }

    public java.util.Set<Identifier> editorContentIds(com.pvzce.api.registry.ResourceKey<? extends com.pvzce.api.registry.Registry<?>> key) {
        var access = workspaceCatalog == null ? com.pvzce.common.core.BuiltInRegistries.ACCESS : workspaceCatalog;
        return access.registries().get(key).keySet();
    }

    public Path editorSourceFile() {
        return sourceFile;
    }

    public void setEditorSourceFile(Path file) {
        this.sourceFile = file;
    }

    public String editorLevelName() {
        return levelName;
    }

    public void setEditorLevelName(String name) {
        this.levelName = name;
    }

    public EditorContext.Rect paletteArea() {
        return new EditorContext.Rect(paletteX, centerY, paletteW, centerH);
    }

    public EditorContext.Rect contentArea() {
        return new EditorContext.Rect(centerX, centerY, centerW, centerH);
    }

    public EditorContext.Rect sideArea() {
        return new EditorContext.Rect(sideX, centerY, sideW, centerH);
    }

    /** The whole content band as a rectangle, for pages that lay themselves out. */
    public EditorContext.Rect fullContentRect() {
        int[] area = fullContentArea();
        return new EditorContext.Rect(area[0], area[1], area[2], area[3]);
    }

    public void setEditorStatus(String text) {
        setStatus(text);
    }

    public void rebuildCurrentPage() {
        buildCurrentPage();
    }

    public <T extends AbstractWidget> T ownPageWidget(T widget) {
        return own(widget);
    }

    public void addPageWidget(AbstractWidget widget) {
        own(widget);
    }

    public void removePageWidget(AbstractWidget widget) {
        pageWidgets.remove(widget);
        widgets.remove(widget);
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    @Override
    public void render() {
        client.beginGuiView();
        renderBackground(0.07F, 0.09F, 0.11F);

        String header = GuiLang.raw("pvzce.editor", "关卡编辑器") + "   " + levelName
                + "   ·   " + levelId + "   ·   " + canvas.width + "×" + canvas.height;
        float headerScale = Math.min(1.05F, Math.max(0.66F, client.guiHeight() / 760F));
        // Drawn to the right of the action buttons, not on top of them: the old title row and
        // the button row shared one line and overlapped at any window size.
        float headerX = paletteX + actionButtonSpan + 16F;
        client.fonts().body().draw(header, headerX, actionY + actionH / 2F - 5F, headerScale, 1F, 1F, 1F, 1F);

        boolean palettePage = currentPage != null && currentPage.hasPalette();
        if (palettePage) {
            drawPanel(paletteX, centerY, paletteW, centerH);
            // Inside the panel: the strip between the nav bar and the panel is already taken by
            // the page label, so a title drawn above it was clipped.
            client.fonts().body().draw(GuiLang.raw("pvzce.editor.palette", "可放置内容"),
                    paletteX + 4, centerY + centerH - 14F, 0.68F, 0.85F, 0.9F, 0.95F, 1F);
            drawPanel(centerX, centerY, centerW, centerH);
            drawPanel(sideX, centerY, sideW, centerH);
        } else {
            // Pages without a palette use the full width: they have two columns of their own
            // and read badly squeezed into the centre column.
            int[] area = fullContentArea();
            drawPanel(area[0], area[1], area[2], area[3]);
        }

        if (currentPage != null) {
            currentPage.render(editorContext);
        }

        for (AbstractWidget widget : widgets) {
            widget.render(client);
        }

        if (!status.isEmpty() && System.nanoTime() < statusUntilNanos) {
            client.fonts().body().draw(status, paletteX + 6, actionY + actionH + 24F, 0.8F, 0.7F, 1F, 0.7F, 1F);
        }
    }

    private void drawPanel(int x, int y, int w, int h) {
        com.pvzce.client.renderer.SpriteRenderer.solid(x, y, w, h, -0.3F, 0.1F, 0.12F, 0.14F, 0.9F);
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    private void setStatus(String message) {
        status = message;
        statusUntilNanos = System.nanoTime() + 4_000_000_000L;
    }

    private void save() {
        if (saveToDisk()) {
            if (workspace == null) client.connection().send(new CommandC2S("/reload"));
            setStatus(GuiLang.raw("pvzce.editor.saved", "已保存：") + levelId);
        }
    }

    /**
     * Writes the level file.
     *
     * <p>Every enabled, applicable page writes its block, including tabs the user collapsed.
     * Unused pages do not inject defaults. Then the draft - which started as a copy of the
     * file as loaded - is written out, so anything no page modelled is still there.
     */
    private boolean saveToDisk() {
        Path previousFile = sourceFile;
        Identifier previousId = levelId;
        flushEditorPages();
        JsonObject root = draft.json();
        Path levelFile = resolveSaveFile();
        try {
            Files.createDirectories(levelFile.getParent());
            Files.writeString(levelFile, root.toString());
            Path pack = workspace == null ? client.gameDir().resolve("datapacks/user_levels") : workspace.root();
            Path mcmeta = pack.resolve("pack.mcmeta");
            if (!Files.exists(mcmeta)) {
                Files.writeString(mcmeta, "{\"pack\":{\"pack_format\":1,\"description\":\"User levels\"}}");
            }
            sourceFile = levelFile;
            sourceJson = root;
            // Only now that the level is safely at its new path is the move applied - and the
            // old file is only removed once the id really changed, so a refused move leaves the
            // level exactly where it was.
            boolean movedGroup = applyGroupChange(previousId, previousFile, levelFile);
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
     * Applies a group change: id, file and save directory all move together.
     *
     * <p>How the move is reported is the caller's business - the fields here only decide
     * <em>whether</em> something moved, which the save path also needs in order to delete
     * the file the level used to live in.
     */
    private boolean applyGroupChange(Identifier previousId, Path previousFile, Path newFile) {
        if (previousId == null || levelId == null || levelId.equals(previousId)) {
            return false;
        }
        // The id has already moved (the info page declares a group change while it writes its
        // block), so the theme and category are read back off the new id: the move then carries
        // the file and the run save to where the id now points.
        com.pvzce.api.util.LevelGrouping.Group group =
                com.pvzce.api.util.LevelGrouping.resolve(levelId, themeIds(), categoryIds());
        LevelMove.Result result = LevelMove.move(previousId, group.theme(), group.category(),
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

    /** Every registered theme, plus the unclassified bucket: what a moved id may point at. */
    private static List<Identifier> themeIds() {
        List<Identifier> ids = new ArrayList<>(
                com.pvzce.common.core.BuiltInRegistries.LEVEL_THEMES.keySet());
        ids.add(com.pvzce.api.util.LevelGrouping.UNCATEGORIZED);
        return ids;
    }

    /** Every registered category, plus the unclassified bucket. */
    private static List<Identifier> categoryIds() {
        List<Identifier> ids = new ArrayList<>(
                com.pvzce.common.core.BuiltInRegistries.LEVEL_CATEGORIES.keySet());
        ids.add(com.pvzce.api.util.LevelGrouping.UNCATEGORIZED);
        return ids;
    }

    /**
     * {@code saves/<world>} for the world the save move should look in, or {@code null}.
     *
     * <p>The client's current world is the one the level was opened from, so it is the one
     * the run save would be under. It is sanitized the way the server does it, so the path
     * this moves and the path the server writes are the same path.
     */
    private Path currentWorldDirectory() {
        if (workspace != null) return null;
        String world = client.currentWorld();
        if (world == null || world.isBlank()) {
            return null;
        }
        return WorldPaths.worldDir(client.gameDir(), world);
    }

    private Path resolveSaveFile() {
        if (workspace != null) return workspace.contentFile("levels", levelId);
        if (sourceFile != null) {
            return sourceFile;
        }
        // The pack root *is* the namespace, so the path is data/<ns>/levels/<path>.json - the
        // same shape the loader reads. It used to spell an extra "pvzce" segment, which meant
        // a level saved by the editor was written to a path no pack would ever load.
        return client.gameDir().resolve("datapacks/user_levels")
                .resolve("data").resolve(levelId.namespace()).resolve("levels")
                .resolve(levelId.path() + ".json");
    }


    /**
     * Plays the level as saved, through the level list's own flow.
     *
     * <p>This used to send {@code /reload} and then start the level directly, which skipped the
     * seed chooser: testing a level and opening it from the list behaved differently. Both now
     * save, reload and hand over to the chooser; {@code testEditedLevel} waits for the reloaded
     * level list so the chooser sees the saved definition rather than the one before the save.
     */
    private void test() {
        if (!saveToDisk()) {
            return;
        }
        if (workspace != null) {
            try { com.pvzce.common.workshop.WorkshopContent.validate(workspace); }
            catch (Exception e) { setStatus(e.getMessage()); return; }
            client.testWorkspace(workspace, levelId.toString());
        } else {
            client.testEditedLevel(levelId.toString());
            client.popScreen();
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        if (modalDialog() != null) {
            return;
        }
        if (currentPage != null) {
            currentPage.onMouseClicked(editorContext, guiX, guiY, button);
        }
    }

    @Override
    public void tick() {
        if (currentPage != null) {
            currentPage.tick(editorContext);
        }
    }

    @Override
    public void keyPressed(int key) {
        com.pvzce.client.gui.components.Dialog modal = modalDialog();
        if (modal != null) {
            modal.keyPressed(key);
            return;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            requestClose();
            return;
        }
        if (currentPage != null && currentPage.keyPressed(editorContext, key)) {
            return;
        }
    }

    /**
     * Leaves the editor, releasing the canvas previews' animations on the way out.
     *
     * <p>The release lives in {@link #onRemoved()} rather than here: the editor can also be
     * replaced out from under itself (the test button hands the screen over to the seed
     * chooser), and a screen that cleans up in its own exit paths is one forgotten exit away
     * from leaking a playback per visit.
     */
    public void requestClose() {
        client.navigateBack();
    }

    @Override
    protected void onRemoved() {
        if (workspaceAssetsAttached) { workspaceAssetsAttached = false; client.previewWorkspaceAssets(previousAssetOverlay); }
        canvas.releaseCanvasAnimations(editorContext);
    }

    // ------------------------------------------------------------------
    // Smoke hooks: drive the editor without a mouse, for screenshots
    // ------------------------------------------------------------------

    public void showPageForSmoke(String key) {
        openEditorModule(key);
    }

    /** Saves without a click, so a smoke run can verify the write round trip. */
    public void saveForSmoke() {
        save();
    }

    /** Places presets without a click, so a smoke run can photograph the canvas. */
    public void placeForSmoke(String kind, String id, int cellX, int cellY) {
        showPage(kind.startsWith("z") ? "zombie" : "plant");
        if (currentPage instanceof CanvasPage canvasPage) {
            canvasPage.placePreset(editorContext, id, cellX, cellY);
        }
    }
}
