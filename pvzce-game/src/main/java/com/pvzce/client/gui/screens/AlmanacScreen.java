package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.almanac.AlmanacEntries;
import com.pvzce.client.input.ScrollRegion;
import com.pvzce.client.renderer.EntityTextures;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.SlotResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * The almanac: the book that replaced the backpack, laid out like the original's Suburban Almanac.
 *
 * <p><b>Layout, from the original.</b> Every page of the original is the same shape, and this is
 * that shape:
 *
 * <pre>
 *   +--------------------------------------------------------------+
 *   |                     title plate (page name)                  |
 *   +---------------------------------------+----------------------+
 *   |  shelf: every entry on this page, one  |  detail card:        |
 *   |  card each, the open one highlighted   |   picture window,    |
 *   |  (and the plant page prints sun cost)  |   name, card line,   |
 *   |                                        |   flavour, numbers   |
 *   +---------------------------------------+----------------------+
 *   |  ALMANAC INDEX                                        CLOSE   |
 *   +--------------------------------------------------------------+
 * </pre>
 *
 * <p>The index is the same book's cover page: two big plates - a brown one for plants, a blue-grey
 * one for zombies - each with a "view" button, and the title plate above them. Both were measured
 * off the original's own screenshots at its own 800x600 and are written down as constants here; the
 * whole page is then contain-fitted to the window, so the composition never changes shape.
 *
 * <p><b>Why a shelf and not one entry per page.</b> The first version of this screen showed one
 * entry at a time with arrows to turn pages, which is not what the original does and reads badly: a
 * book whose whole point is "what is this thing" wants the shelf visible next to the open page, so
 * a reader can compare and browse. The original's shelf also carries each plant's sun cost on its
 * card, which is the number a player is actually comparing.
 *
 * <p>Resources have no page in the original (it never had a resource system), so that page reuses
 * the plants layout: same browns, same card shelf, its value printed where a plant prints its sun
 * cost.
 */
public final class AlmanacScreen extends Screen {
    /** The canvas the original draws on; every measurement below is in these units. */
    private static final float NATIVE_WIDTH = 800F;
    private static final float NATIVE_HEIGHT = 600F;

    private static final Identifier BACKGROUND_INDEX =
            Identifier.withDefaultNamespace("textures/gui/almanac/index_background");
    private static final Identifier BACKGROUND_PLANTS =
            Identifier.withDefaultNamespace("textures/gui/almanac/plant_background");
    private static final Identifier BACKGROUND_ZOMBIES =
            Identifier.withDefaultNamespace("textures/gui/almanac/zombie_background");
    private static final Identifier CARD_PLANT =
            Identifier.withDefaultNamespace("textures/gui/almanac/plant_card");
    private static final Identifier CARD_ZOMBIE =
            Identifier.withDefaultNamespace("textures/gui/almanac/zombie_card");
    private static final Identifier BUTTON_INDEX =
            Identifier.withDefaultNamespace("textures/gui/almanac/button_index");
    private static final Identifier BUTTON_INDEX_LIT =
            Identifier.withDefaultNamespace("textures/gui/almanac/button_index_highlight");
    private static final Identifier BUTTON_CLOSE =
            Identifier.withDefaultNamespace("textures/gui/almanac/button_close");
    private static final Identifier BUTTON_CLOSE_LIT =
            Identifier.withDefaultNamespace("textures/gui/almanac/button_close_highlight");
    private static final Identifier LOCK_BADGE =
            Identifier.withDefaultNamespace("textures/gui/icon/lock");
    /**
     * The plot an entry stands on, which is the original's own art for it.
     *
     * <p>Every entry in the original is drawn on grass - "the background of each plant's animation
     * varies according to the environment it can be used in, but all zombies have the same
     * background, which is the grassy backdrop in the Day levels". This project has only the lawn
     * plot, so every entry gets it, and a per-environment variant is a matter of picking a
     * different id from the level's `scene` of the level the entry belongs to.
     */
    private static final Identifier GROUND_DAY =
            Identifier.withDefaultNamespace("textures/gui/screen/level/almanac_groundday");

    // ------------------------------------------------------------------------------------------
    // Geometry, measured off the original's own screenshots at 800x600.
    // ------------------------------------------------------------------------------------------

    /**
     * The title band across the top of every page.
     *
     * <p>Every background paints its own stone bar - measured, they run from about y 16 to y 66,
     * x 50 to 750 - and this band sits inside it. It is drawn <em>over</em> that bar rather than
     * replacing it, so the bar's moulded edges stay visible and the page name has a flat place to
     * sit on all three backgrounds, whose bars are three slightly different paints.
     */
    private static final float TITLE_X = 54F;
    private static final float TITLE_Y = 18F;
    private static final float TITLE_W = 692F;
    private static final float TITLE_H = 46F;
    private static final float TITLE_BASELINE = 52F;

    /** The shelf of entry cards. */
    private static final float GRID_X = 24F;
    private static final float GRID_Y = 92F;
    private static final int GRID_COLUMNS = 8;
    /**
     * How many rows the shelf has.
     *
     * <p>Four, which is what fits between the title band and the footer at the original's pitch -
     * the original ships exactly four rows of eight on its plant page and five of five on its
     * zombie page. A page a mod makes longer than that is walked with the arrow keys rather than
     * drawn over the frame.
     */
    private static final int GRID_ROWS = 4;

    /**
     * A shelf card's size and pitch, per page.
     *
     * <p>The original's plant cards are the seed-packet shape (46x70 on a 52x80 pitch, eight to a
     * row); its zombie cards are near-square and wider (76x87 on an 82x80 pitch, five to a row).
     * Two shapes rather than one because that is what the original ships, and the zombie card needs
     * the width for a walking figure where the plant card needs the height for a packet.
     */
    private static final float PLANT_CARD_W = 46F;
    private static final float PLANT_CARD_H = 70F;
    private static final float PLANT_PITCH_X = 52F;
    private static final float PLANT_PITCH_Y = 80F;

    private static final float ZOMBIE_CARD_W = 76F;
    private static final float ZOMBIE_CARD_H = 87F;
    private static final float ZOMBIE_PITCH_X = 82F;
    private static final float ZOMBIE_PITCH_Y = 80F;

    /** The detail card, on the right. Both frames are drawn 1:1, as the original does. */
    private static final float DETAIL_X = 458F;
    private static final float DETAIL_Y = 76F;

    /**
     * The two frames' own size, and where their windows sit inside them.
     *
     * <p>Read off the two PNGs. The plant frame's windows are the white picture box and the beige
     * text panel; the zombie frame's are the dark picture box and the violet text panel - and they
     * are <em>not</em> at the same fractions of the card, which is why they are written down per
     * frame instead of computed.
     */
    private static final float PLANT_ART_W = 324F;
    private static final float PLANT_ART_H = 484F;
    private static final float PLANT_IMAGE_X = 71F;
    private static final float PLANT_IMAGE_Y = 32F;
    private static final float PLANT_IMAGE_W = 181F;
    private static final float PLANT_IMAGE_H = 133F;
    private static final float PLANT_TEXT_X = 24F;
    private static final float PLANT_TEXT_Y = 223F;
    private static final float PLANT_TEXT_W = 264F;
    private static final float PLANT_TEXT_H = 231F;

    private static final float ZOMBIE_ART_W = 324F;
    private static final float ZOMBIE_ART_H = 497F;
    private static final float ZOMBIE_IMAGE_X = 72F;
    private static final float ZOMBIE_IMAGE_Y = 58F;
    private static final float ZOMBIE_IMAGE_W = 183F;
    private static final float ZOMBIE_IMAGE_H = 170F;
    private static final float ZOMBIE_TEXT_X = 28F;
    private static final float ZOMBIE_TEXT_Y = 298F;
    private static final float ZOMBIE_TEXT_W = 264F;
    private static final float ZOMBIE_TEXT_H = 162F;

    /**
     * How much of the world an animated preview shows, in cells.
     *
     * <p>Under one cell, so the figure fills the window: a zombie is drawn about 0.7 cells tall and
     * a window showing a whole cell would leave it rattling around in the middle of an empty square.
     */
    private static final float PREVIEW_CELLS = 0.85F;

    /** The footer buttons: "Almanac Index" bottom-left, "Close" bottom-right. */
    private static final float FOOTER_Y = 566F;
    private static final float FOOTER_H = 28F;
    private static final float INDEX_BUTTON_X = 20F;
    private static final float INDEX_BUTTON_W = 164F;
    private static final float CLOSE_BUTTON_W = 89F;
    private static final float CLOSE_BUTTON_X = NATIVE_WIDTH - 20F - CLOSE_BUTTON_W;

    /** The index's two plates, measured off the original's index screenshot. */
    private static final float INDEX_PLANT_X = 27F;
    private static final float INDEX_PLANT_Y = 172F;
    private static final float INDEX_PLANT_W = 363F;
    private static final float INDEX_PLANT_H = 240F;
    private static final float INDEX_ZOMBIE_X = 410F;
    private static final float INDEX_ZOMBIE_Y = 165F;
    private static final float INDEX_ZOMBIE_W = 365F;
    private static final float INDEX_ZOMBIE_H = 260F;
    private static final float VIEW_BUTTON_W = 160F;
    private static final float VIEW_BUTTON_H = 26F;

    // ------------------------------------------------------------------------------------------
    // Palette. The original prints plants in gold on brown and zombies in green on violet.
    // ------------------------------------------------------------------------------------------

    private static final float[] PLANT_GOLD = {212F / 255F, 158F / 255F, 42F / 255F, 1F};
    private static final float[] PLANT_INK = {82F / 255F, 29F / 255F, 11F / 255F, 1F};
    private static final float[] PLANT_CARD_FILL = {252F / 255F, 206F / 255F, 140F / 255F, 1F};
    private static final float[] PLANT_CARD_EDGE = {124F / 255F, 48F / 255F, 15F / 255F, 1F};
    private static final float[] PLANT_SELECTED_FILL = {255F / 255F, 233F / 255F, 180F / 255F, 1F};
    private static final float[] PLANT_BUTTON_FILL = {168F / 255F, 108F / 255F, 56F / 255F, 1F};

    private static final float[] ZOMBIE_GREEN = {0F, 196F / 255F, 0F, 1F};
    private static final float[] ZOMBIE_INK = {13F / 255F, 128F / 255F, 13F / 255F, 1F};
    private static final float[] ZOMBIE_CARD_FILL = {80F / 255F, 82F / 255F, 117F / 255F, 1F};
    private static final float[] ZOMBIE_CARD_EDGE = {46F / 255F, 44F / 255F, 66F / 255F, 1F};
    private static final float[] ZOMBIE_SELECTED_FILL = {118F / 255F, 122F / 255F, 172F / 255F, 1F};
    private static final float[] ZOMBIE_BUTTON_FILL = {96F / 255F, 98F / 255F, 140F / 255F, 1F};

    /** The stone plate the title sits on. */
    private static final float[] PLATE_FILL = {150F / 255F, 152F / 255F, 172F / 255F, 1F};
    private static final float[] PLATE_EDGE = {78F / 255F, 80F / 255F, 100F / 255F, 1F};

    private static final float[] BLACK = {0F, 0F, 0F, 1F};

    /** Font scales, as every other screen in this project writes them (0.7 .. 1.5). */
    private static final float TITLE_SCALE = 1.2F;
    private static final float VIEW_LABEL_SCALE = 1.05F;
    private static final float CARD_COST_SCALE = 0.7F;
    private static final float DETAIL_NAME_SCALE = 0.9F;
    private static final float DETAIL_BODY_SCALE = 0.6F;
    private static final float DETAIL_STAT_SCALE = 0.6F;
    private static final float BUTTON_SCALE = 0.8F;

    // ------------------------------------------------------------------------------------------

    /** One catalogue per page, in the order the index lists them. */
    private final List<AlmanacEntries.Catalogue> catalogues = new ArrayList<>();
    /** {@code -1} shows the index; anything else is an index into {@link #catalogues}. */
    private int page = -1;
    /** The entry the detail card is showing. */
    private int selected;
    /** A page asked for before {@link #init()} ran, applied by it. */
    private AlmanacEntries.Page wanted;

    private float scale = 1F;
    private float originX;
    private float originY;

    /** A clickable rect in native units, with what it does. */
    private record Hit(float x, float y, float w, float h, Runnable action) {
        boolean contains(float nx, float ny) {
            return nx >= x && nx <= x + w && ny >= y && ny <= y + h;
        }
    }

    private final List<Hit> hits = new ArrayList<>();

    /**
     * One preview entity per entry, built on first use and released with the screen.
     *
     * <p>Every entry with a rig animates, on the shelf and in the detail card alike: the original's
     * entries do, and a rig drawn into a box keeps its own proportions - which a stretched seed
     * packet does not (the first version drew the packet icon into a box of a different shape and
     * a Peashooter came out half as wide as it is tall).
     *
     * <p>Built lazily and kept, rather than rebuilt per frame: a page holds up to 32 entries and
     * building a playback for each one every frame would leak a playback per frame. Anything the
     * client cannot animate (a resource, or content whose definition names no rig) has no entry
     * here and falls back to its sprite.
     */
    private final java.util.Map<Identifier, ClientEntity> previews = new java.util.HashMap<>();

    public AlmanacScreen(PvzceClient client) {
        super(client);
    }

    @Override
    protected void init() {
        // Both are absent in a windowless test harness, where a screen is built to be inspected
        // rather than played; the almanac draws fine either way.
        if (client.music() != null) {
            client.music().ensureMenu("pvzce:music/choose_your_seeds");
        }
        // The profile travels with the level list, and the plant page draws lock state from it.
        if (client.connection() != null) {
            client.connection().send(new com.pvzce.common.network.packet.RequestLevelListC2S(
                    client.currentWorld()));
        }
        catalogues.clear();
        catalogues.addAll(AlmanacEntries.all());
        if (wanted != null) {
            AlmanacEntries.Page target = wanted;
            wanted = null;
            openPage(target);
        }
    }

    @Override
    protected void onRemoved() {
        releasePreviews();
    }

    /**
     * The live preview for an entry, built on first use.
     *
     * <p>{@code null} when the entry has no rig the client can play, which is the caller's cue to
     * draw its sprite instead.
     */
    private ClientEntity previewFor(AlmanacEntries.Page openPage, Identifier id) {
        if (id == null || client.animations() == null) {
            return null;
        }
        ClientEntity cached = previews.get(id);
        if (cached != null) {
            return cached;
        }
        // Only content with a rig: a resource has no animation file, and an entity built for one
        // would leave a playback that never draws anything.
        if (com.pvzce.common.core.EntityArt.animationFile(id) == null) {
            return null;
        }
        ClientEntity entity = new ClientEntity(-1 - previews.size(), openPage.entityKind(),
                id.toString(), 0.5F, 0.5F, 100, 1, "idle", 0F, "");
        entity.attachAnimationManager(client.animations());
        previews.put(id, entity);
        return entity;
    }

    /** Drops every preview's playback; called once, when the screen goes away. */
    private void releasePreviews() {
        if (client.animations() != null) {
            for (ClientEntity entity : previews.values()) {
                client.animations().release(entity);
            }
        }
        previews.clear();
    }

    @Override
    public void tick() {
        for (ClientEntity entity : previews.values()) {
            entity.update(0.5F, 0.5F, 100, "idle", 0F);
            entity.playAnimation("idle");
        }
    }

    private AlmanacEntries.Catalogue current() {
        return page < 0 || page >= catalogues.size() ? null : catalogues.get(page);
    }

    /** Back to the index, whether or not the screen has been initialized yet. */
    public void showIndex() {
        page = -1;
        selected = 0;
        if (catalogues.isEmpty()) {
            wanted = null;
        }
    }

    private void openPage(AlmanacEntries.Page wantedPage) {
        for (int i = 0; i < catalogues.size(); i++) {
            if (catalogues.get(i).page() == wantedPage) {
                page = i;
                selected = 0;
                return;
            }
        }
    }

    /**
     * Opens a page without a click, so a screenshot run can reach it.
     *
     * <p>Safe to call before the screen has been initialized: the request is remembered and applied
     * by {@link #init()}, which otherwise runs on the first rendered frame and would discard it.
     */
    public void show(AlmanacEntries.Page target) {
        if (catalogues.isEmpty()) {
            wanted = target;
            return;
        }
        openPage(target);
    }

    /** Selects an entry by index; for the smoke driver's screenshot runs. */
    public void selectEntry(int index) {
        selected = index;
    }

    @Override
    public void render() {
        client.beginGuiView();
        computeLayout();
        hits.clear();

        client.drawSolid(0, 0, client.guiWidth(), client.guiHeight(), -2F, 0.09F, 0.05F, 0.03F, 1F);
        if (page < 0) {
            renderIndex();
        } else {
            renderCatalogue();
        }
    }

    /**
     * One scale for the whole page, and it is a <em>contain</em> fit: the book's own frame is
     * inside the 800x600 image, so cover-fitting a 16:9 window would crop the title plate and the
     * footer buttons away.
     */
    private void computeLayout() {
        scale = Math.min(client.guiWidth() / NATIVE_WIDTH, client.guiHeight() / NATIVE_HEIGHT);
        originX = (client.guiWidth() - NATIVE_WIDTH * scale) / 2F;
        originY = (client.guiHeight() - NATIVE_HEIGHT * scale) / 2F;
    }

    private float sx(float nativeX) {
        return originX + nativeX * scale;
    }

    /** Native space grows downward from the top of the art; GUI space grows upward. */
    private float sy(float nativeY) {
        return originY + (NATIVE_HEIGHT - nativeY) * scale;
    }

    private float sw(float nativeLength) {
        return nativeLength * scale;
    }

    private float nativeX(double guiX) {
        return (float) ((guiX - originX) / scale);
    }

    private float nativeY(double guiY) {
        return NATIVE_HEIGHT - (float) ((guiY - originY) / scale);
    }

    // ------------------------------------------------------------------------------------------
    // Index
    // ------------------------------------------------------------------------------------------

    private void renderIndex() {
        drawBackground(BACKGROUND_INDEX);
        drawTitlePlate(GuiLang.raw("gui.pvzce.almanac.index_title", "Almanac - Index"), PLANT_GOLD);

        // Two plates, in the original's own colours and positions: brown wood for plants, blue-grey
        // stone for zombies, each with the page's first entry standing on it and a "view" label at
        // its foot. Drawn as plates rather than as scaled card frames - a 324x484 frame squeezed
        // into a 363x240 box fits at 49% and leaves the plate three quarters empty, which is what
        // the first version looked like.
        drawPlate(AlmanacEntries.Page.PLANTS, INDEX_PLANT_X, INDEX_PLANT_Y,
                INDEX_PLANT_W, INDEX_PLANT_H);
        drawPlate(AlmanacEntries.Page.ZOMBIES, INDEX_ZOMBIE_X, INDEX_ZOMBIE_Y,
                INDEX_ZOMBIE_W, INDEX_ZOMBIE_H);

        viewButton(GuiLang.raw("gui.pvzce.almanac.view_plants", "View Plants"),
                AlmanacEntries.Page.PLANTS,
                INDEX_PLANT_X + (INDEX_PLANT_W - VIEW_BUTTON_W) / 2F,
                INDEX_PLANT_Y + INDEX_PLANT_H - 44F, PLANT_GOLD, PLANT_INK);
        viewButton(GuiLang.raw("gui.pvzce.almanac.view_zombies", "View Zombies"),
                AlmanacEntries.Page.ZOMBIES,
                INDEX_ZOMBIE_X + (INDEX_ZOMBIE_W - VIEW_BUTTON_W) / 2F,
                INDEX_ZOMBIE_Y + INDEX_ZOMBIE_H - 44F, ZOMBIE_GREEN, ZOMBIE_INK);

        // The previews last, because the rig path moves the projection. Each stands above its
        // label, in the plate's own upper area.
        drawIndexPreview(AlmanacEntries.Page.PLANTS, INDEX_PLANT_X + 112F, INDEX_PLANT_Y + 22F,
                140F, 118F);
        drawIndexPreview(AlmanacEntries.Page.ZOMBIES, INDEX_ZOMBIE_X + 108F, INDEX_ZOMBIE_Y + 18F,
                150F, 150F);

        drawFooter(false);
    }

    /**
     * The index plate's preview: the page's first entry, standing on its plot.
     *
     * <p>Called after the label, because the rig path moves the projection.
     */
    private void drawIndexPreview(AlmanacEntries.Page openPage, float x, float y, float w, float h) {
        Identifier id = firstOf(openPage);
        drawGround(x, y, w, h);
        ClientEntity entity = previewFor(openPage, id);
        if (entity == null) {
            drawPreview(openPage, id, x, y, w, h);
        } else {
            drawRig(entity, x, y, w, h);
        }
    }

    /** One of the index's two plates: a filled panel with the page's own colours. */
    private void drawPlate(AlmanacEntries.Page openPage, float x, float y, float w, float h) {
        boolean zombies = openPage == AlmanacEntries.Page.ZOMBIES;
        float[] edge = zombies ? ZOMBIE_CARD_EDGE : PLANT_CARD_EDGE;
        float[] fill = zombies ? new float[]{74F / 255F, 62F / 255F, 52F / 255F}
                : new float[]{108F / 255F, 48F / 255F, 20F / 255F};
        float border = 10F;
        client.drawSolid(sx(x), sy(y + h), sw(w), sw(h), 0.1F, edge[0], edge[1], edge[2], 1F);
        client.drawSolid(sx(x + border), sy(y + h - border), sw(w - border * 2F),
                sw(h - border * 2F), 0.1F, fill[0], fill[1], fill[2], 1F);
        hits.add(new Hit(x, y, w, h, () -> openPage(openPage)));
    }

    /** The first id of a page's catalogue, or {@code null} when it has none. */
    private Identifier firstOf(AlmanacEntries.Page wantedPage) {
        for (AlmanacEntries.Catalogue catalogue : catalogues) {
            if (catalogue.page() == wantedPage) {
                return catalogue.at(0);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Catalogue page: shelf on the left, detail on the right
    // ------------------------------------------------------------------------------------------

    private void renderCatalogue() {
        AlmanacEntries.Catalogue catalogue = current();
        if (catalogue == null) {
            showIndex();
            return;
        }
        AlmanacEntries.Page openPage = catalogue.page();
        drawBackground(backgroundFor(openPage));
        drawTitlePlate(pageTitle(openPage), inkFor(openPage));

        if (catalogue.size() == 0) {
            drawTextLeft(GuiLang.raw("gui.pvzce.almanac.empty", "Nothing here yet"),
                    40F, 210F, 1.2F, inkFor(openPage));
            drawFooter(true);
            return;
        }
        if (selected < 0 || selected >= catalogue.size()) {
            selected = 0;
        }

        renderShelf(catalogue);
        Identifier id = catalogue.at(selected);
        // Detail card, in this order and no other:
        //   1. every opaque layer (the frame, then the picture window's own fill),
        //   2. the text - the frame's painted panel would cover it otherwise, which is exactly
        //      the bug this order exists to prevent,
        //   3. the animated preview last, because it switches the world projection on the way in
        //      and the GUI one on the way out, and text drawn while the world projection is up
        //      comes out at world scale.
        renderDetailCard(openPage, id);
        renderDetailText(openPage, id);
        renderDetailPreview(openPage, id);
        drawFooter(true);
    }

    /**
     * The shelf: every entry on this page, one card each, the open one highlighted.
     *
     * <p>Laid out at the original's pitch, and each card draws the entry's own art plus - on the
     * plant page - its sun cost, which is the number the original prints there and the only number
     * a reader compares across the shelf.
     */
    private void renderShelf(AlmanacEntries.Catalogue catalogue) {
        AlmanacEntries.Page openPage = catalogue.page();
        boolean zombies = openPage == AlmanacEntries.Page.ZOMBIES;
        float cardW = zombies ? ZOMBIE_CARD_W : PLANT_CARD_W;
        float cardH = zombies ? ZOMBIE_CARD_H : PLANT_CARD_H;
        float pitchX = zombies ? ZOMBIE_PITCH_X : PLANT_PITCH_X;
        float pitchY = zombies ? ZOMBIE_PITCH_Y : PLANT_PITCH_Y;

        int shown = Math.min(catalogue.size(), GRID_COLUMNS * GRID_ROWS);
        for (int i = 0; i < shown; i++) {
            int column = i % GRID_COLUMNS;
            int row = i / GRID_COLUMNS;
            float x = GRID_X + column * pitchX;
            float y = GRID_Y + row * pitchY;
            drawShelfCard(openPage, catalogue.at(i), x, y, cardW, cardH, i == selected);
            final int index = i;
            hits.add(new Hit(x, y, cardW, cardH, () -> selected = index));
        }
    }

    private void drawShelfCard(AlmanacEntries.Page openPage, Identifier id, float x, float y,
                               float w, float h, boolean selected) {
        boolean zombies = openPage == AlmanacEntries.Page.ZOMBIES;
        float[] fill = zombies
                ? (selected ? ZOMBIE_SELECTED_FILL : ZOMBIE_CARD_FILL)
                : (selected ? PLANT_SELECTED_FILL : PLANT_CARD_FILL);
        float[] edge = zombies ? ZOMBIE_CARD_EDGE : PLANT_CARD_EDGE;

        // A filled packet with a one-unit border, which is what the original's shelf cards are;
        // the content's own art is drawn on top of it.
        client.drawSolid(sx(x), sy(y + h), sw(w), sw(h), 0.1F, edge[0], edge[1], edge[2], 1F);
        client.drawSolid(sx(x + 2F), sy(y + h - 2F), sw(w - 4F), sw(h - 4F), 0.1F,
                fill[0], fill[1], fill[2], 1F);

        // The card's picture area leaves room underneath for the cost line (plants) or a little
        // breathing space (zombies). A zombie has no whole-body sprite, so its card is drawn from
        // its rig; a plant could go either way and uses the rig too, so nothing on this page is a
        // stretched packet.
        float artBottom = zombies ? y + h - 6F : y + h - 20F;
        float artTop = artBottom - (h - 12F);
        if (zombies) {
            drawGround(x + 3F, artTop, w - 6F, h - 12F);
        }
        ClientEntity shelfEntity = previewFor(openPage, id);
        if (shelfEntity != null) {
            drawRig(shelfEntity, x + 3F, artTop, w - 6F, h - 12F);
        } else if (!zombies) {
            drawPreview(openPage, id, x + 4F, artTop, w - 8F, h - 12F);
        }

        if (!zombies) {
            // The number the shelf is for: what it costs. Plants print the sun cost, resources
            // their value.
            String amount = String.valueOf(openPage == AlmanacEntries.Page.RESOURCES
                    ? resourceValue(id) : plantCost(id));
            float fontScale = CARD_COST_SCALE;
            float textW = width(amount, fontScale);
            Identifier sun = sunIcon();
            float iconSize = sun == null ? 0F : 11F;
            float total = textW + (sun == null ? 0F : 3F + iconSize);
            float textX = x + (w - total) / 2F;
            drawTextLeft(amount, textX, y + h - 7F, fontScale, PLANT_INK);
            if (sun != null) {
                client.drawTexture(sun, sx(textX + textW + 3F), sy(y + h - 3F),
                        sw(iconSize), sw(iconSize), 0.2F, 1F, 1F, 1F, 1F);
            }
        }

        if (!isUnlocked(openPage, id)) {
            // Locked plants are listed and dimmed with the lock badge the rest of the game uses.
            // The original only lists what you own; showing what is missing is this project's own
            // call, because "why can't I plant a wall-nut" is the question the shelf answers.
            client.drawSolid(sx(x + 2F), sy(y + h - 2F), sw(w - 4F), sw(h - 4F), 0.3F,
                    0.05F, 0.05F, 0.05F, 0.45F);
            if (client.hasTexture(LOCK_BADGE)) {
                float size = Math.min(w, h) * 0.4F;
                client.drawTexture(LOCK_BADGE, sx(x + (w - size) / 2F), sy(y + h * 0.55F + size / 2F),
                        sw(size), sw(size), 0.4F, 1F, 1F, 1F, 0.95F);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Detail card
    // ------------------------------------------------------------------------------------------

    /**
     * The detail card's opaque layers: the frame, and the fill of its picture window.
     *
     * <p>The window is filled with the card's own colour rather than left transparent, because the
     * text of the entry behind it must not show through - the frame's painted panel is opaque and
     * the window is not.
     */
    private void renderDetailCard(AlmanacEntries.Page openPage, Identifier id) {
        drawCardArt(openPage, DETAIL_X, DETAIL_Y, artWidth(openPage), artHeight(openPage));
        float[] image = imageWindow(openPage);
        // The plot the entry stands on, inside the frame's picture window.
        drawGround(DETAIL_X + image[0], DETAIL_Y + image[1], image[2], image[3]);
    }

    /**
     * The detail card's picture.
     *
     * <p>Called <em>after</em> {@link #renderDetailText}: the animated path switches the world
     * projection and switches it back, and anything drawn while it is active comes out at world
     * scale.
     */
    private void renderDetailPreview(AlmanacEntries.Page openPage, Identifier id) {
        float[] image = imageWindow(openPage);
        float imageX = DETAIL_X + image[0];
        float imageY = DETAIL_Y + image[1];
        ClientEntity entity = previewFor(openPage, id);
        if (entity == null) {
            drawPreview(openPage, id, imageX, imageY, image[2], image[3]);
            return;
        }
        drawRig(entity, imageX, imageY, image[2], image[3]);
    }

    /**
     * Draws a live rig into a box, in the box's own viewport.
     *
     * <p>Three things this has to get right, each of which was a bug on its own:
     * <ul>
     *   <li>the world rect is {@link #PREVIEW_CELLS} (under a cell), so the figure fills the box
     *       instead of rattling around an empty lawn square;
     *   <li>the playback is started <em>here</em>, not only in {@link #tick()}: a preview is built
     *       lazily on the frame it is first drawn, and that frame's tick has already run, so an
     *       entity that only ever played there drew nothing at all - which looked like "every
     *       zombie card is empty";
     *   <li>the GUI view is restored on the way out, because the world view sets the sprite scale
     *       too and the page's text is drawn in GUI space.
     * </ul>
     */
    private void drawRig(ClientEntity entity, float x, float y, float w, float h) {
        if (entity == null || w <= 0F || h <= 0F) {
            return;
        }
        entity.playAnimation("idle");
        client.clipping().push(sx(x), sy(y + h), sw(w), sw(h));
        try {
            client.beginOverlayWorldView(sx(x), sy(y + h), sw(w), sw(h),
                    0F, PREVIEW_CELLS, 0F, PREVIEW_CELLS);
            client.animations().render(entity);
        } finally {
            client.clipping().pop();
            client.beginGuiView();
        }
    }

    /**
     * A live entity for an entry, so the detail panel can animate it.
     *
     * <p>Plants animate too: the original's entries do, and a plant's rig file is right there. The
     * preview is built for whatever entry is open and released when it changes, so at most one
     * playback per page is alive at a time.
     */
    private ClientEntity anyPreview(AlmanacEntries.Page openPage, Identifier id) {
        return previewFor(openPage, id);
    }

    /**
     * The detail card's text.
     *
     * <p>One flow, from the top of the card's painted text panel downwards: the name, the card's
     * own line, the numbers, the bio. The original prints exactly this and does not box the parts
     * apart, and the panel is the hard boundary.
     *
     * <p><b>How the boundary is kept.</b> By layout, not by a scissor: {@link #drawWrapped} is given
     * the panel's floor and drops any line that would fall past it. Pushing a clip here looked
     * right and was not - the card art draw ends by restoring the GUI view, and the clip does not
     * survive that, so the scissor silently stopped applying part way through the panel and the bio
     * printed over the picture window.
     *
     * <p>Called <em>before</em> {@link #renderDetailPreview}: the animated preview sets the world
     * projection and restores the GUI one, and text drawn in between comes out at world scale.
     */
    private void renderDetailText(AlmanacEntries.Page openPage, Identifier id) {
        boolean zombies = openPage == AlmanacEntries.Page.ZOMBIES;
        float[] text = textWindow(openPage);
        float textX = DETAIL_X + text[0];
        float textY = DETAIL_Y + text[1];
        float textW = text[2];
        float textH = text[3];

        float[] accent = zombies ? ZOMBIE_GREEN : PLANT_GOLD;
        float[] ink = zombies ? ZOMBIE_INK : PLANT_INK;
        // The panel's four bands, top to bottom: the name, the card line and the numbers, the bio,
        // and the two numbers the original prints at the foot of the card.
        float nameBaseline = textY + textH - 16F;
        float bodyTop = nameBaseline - 16F;
        float floor = textY + 30F;

        drawLineCentredIn(textX, textW, GuiLang.name(openPage.category(), id),
                nameBaseline, DETAIL_NAME_SCALE, accent);

        float cursor = drawWrapped(GuiLang.contentOr(openPage.category(), id, "desc", ""),
                textX, bodyTop, textW, DETAIL_BODY_SCALE, ink, floor);
        for (String line : statLines(openPage, id)) {
            cursor = drawWrapped(line, textX, cursor - 4F, textW, DETAIL_STAT_SCALE, ink, floor) - 2F;
        }
        String flavor = GuiLang.content(openPage.category(), id, "flavor");
        if (flavor != null && !flavor.isBlank()) {
            drawWrapped(flavor, textX, cursor - 4F, textW, DETAIL_BODY_SCALE, ink, floor);
        }
        drawBottomBand(openPage, id, textX, textY, textW);
        if (!isUnlocked(openPage, id)) {
            drawLineCentredIn(textX, textW,
                    GuiLang.raw("gui.pvzce.almanac.locked", "Not yet unlocked"),
                    textY + 11F, DETAIL_BODY_SCALE, ink);
        }
    }

    /** The card's bottom band: what the plant costs and how long it takes to come back. */
    private void drawBottomBand(AlmanacEntries.Page openPage, Identifier id, float textX, float textY,
                                float textW) {
        if (openPage != AlmanacEntries.Page.PLANTS) {
            return;
        }
        int cost = plantCost(id);
        int cooldown = plantCooldown(id);
        String left = GuiLang.raw("gui.pvzce.almanac.stat.cost", "Cost") + " " + cost;
        String right = cooldown <= 0 ? "" : GuiLang.raw("gui.pvzce.almanac.stat.recharge", "Recharge")
                + " " + GuiLang.raw("gui.pvzce.almanac.seconds", "{0}s")
                        .replace("{0}", trim(cooldown / 60F));
        float baseline = textY + 15F;
        float[] ink = openPage == AlmanacEntries.Page.ZOMBIES ? ZOMBIE_INK : PLANT_INK;
        drawTextLeft(left, textX, baseline, DETAIL_STAT_SCALE, ink);
        if (!right.isEmpty()) {
            client.fonts().body().draw(right,
                    sx(textX + textW) - client.fonts().body().width(right, DETAIL_STAT_SCALE),
                    sy(baseline), DETAIL_STAT_SCALE, ink[0], ink[1], ink[2], 1F);
        }
    }

    /**
     * The numbers, as lines of text in the detail panel.
     *
     * <p>Read from the definitions rather than the language file, so the book cannot advertise a
     * value the simulation does not use. The original's vocabulary is kept where it fits - a plant
     * shows a damage figure, a zombie a toughness figure - and this project's own numbers fill
     * them.
     */
    private List<String> statLines(AlmanacEntries.Page openPage, Identifier id) {
        List<String> lines = new ArrayList<>();
        switch (openPage) {
            case PLANTS -> {
                var plant = BuiltInRegistries.PLANTS.get(id);
                if (plant != null) {
                    lines.add(stat("damage", "Damage") + " " + plant.health());
                }
            }
            case ZOMBIES -> {
                var zombie = BuiltInRegistries.ZOMBIES.get(id);
                if (zombie != null) {
                    lines.add(stat("toughness", "Toughness") + " " + zombie.health());
                    lines.add(stat("speed", "Speed") + " " + trim(zombie.moveSpeed() * 60F)
                            + GuiLang.raw("gui.pvzce.almanac.cells_per_second", " cells/s"));
                    lines.add(stat("bite", "Bite") + " " + zombie.biteDamage());
                }
            }
            case RESOURCES -> {
                var resource = BuiltInRegistries.RESOURCES.get(id);
                if (resource != null) {
                    lines.add(stat("value", "Value") + " " + resource.defaultValue());
                }
            }
        }
        return lines;
    }

    /** A stat label, as the original prints "Damage:" and "Recharge:". */
    private String stat(String key, String fallback) {
        return GuiLang.raw("gui.pvzce.almanac.stat." + key, fallback) + ":";
    }

    /** A number with at most one decimal, and no trailing {@code .0}. */
    private static String trim(float value) {
        String text = String.format(java.util.Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    /** The sun a plant card costs, resolved through its slot when one exists. */
    private int plantCost(Identifier id) {
        var slot = BuiltInRegistries.SLOT_TYPES.get(id);
        if (slot != null && slot.cost().amountOf(com.pvzce.common.PvzceIds.SUN) > 0) {
            return slot.cost().amountOf(com.pvzce.common.PvzceIds.SUN);
        }
        var plant = BuiltInRegistries.PLANTS.get(id);
        return plant == null ? 0 : plant.cost().amountOf(com.pvzce.common.PvzceIds.SUN);
    }

    /** A plant card's recharge in ticks: its slot's, or its own definition's. */
    private int plantCooldown(Identifier id) {
        var slot = BuiltInRegistries.SLOT_TYPES.get(id);
        if (slot != null && slot.cost().cooldownTicks() > 0) {
            return slot.cost().cooldownTicks();
        }
        var plant = BuiltInRegistries.PLANTS.get(id);
        return plant == null ? 0 : plant.cost().cooldownTicks();
    }

    private int resourceValue(Identifier id) {
        var resource = BuiltInRegistries.RESOURCES.get(id);
        return resource == null ? 0 : resource.defaultValue();
    }

    private Identifier sunIcon() {
        var sun = BuiltInRegistries.RESOURCES.get(com.pvzce.common.PvzceIds.SUN);
        Identifier icon = sun == null ? null : sun.icon();
        return icon != null && client.hasTexture(icon) ? icon : null;
    }

    /**
     * Whether the player may use this entry.
     *
     * <p>Plants are the backpack's business, exactly as {@link SlotResolver#requiresUnlock} defines
     * it; resources and zombies never lock - this project has no record of which zombies a player
     * has met, so the book shows them all rather than pretending to a knowledge it does not have.
     */
    private boolean isUnlocked(AlmanacEntries.Page openPage, Identifier id) {
        if (openPage != AlmanacEntries.Page.PLANTS) {
            return true;
        }
        // The card id is the plant id for every plant this project ships, but a pack may grant a
        // plant through a differently named slot, so a card is only assumed when one exists.
        Identifier card = BuiltInRegistries.SLOT_TYPES.containsKey(id)
                ? id
                : BuiltInRegistries.SLOT_TYPES.keySet().stream()
                        .filter(slotId -> id.equals(SlotResolver.resolve(slotId)
                                .map(SlotResolver.ResolvedCard::content).orElse(null)))
                        .findFirst()
                        .orElse(id);
        return SlotResolver.owns(client.profile().unlocked(), client.profile().unlockAll(), card);
    }

    // ------------------------------------------------------------------------------------------
    // Per-page presentation
    // ------------------------------------------------------------------------------------------

    private Identifier backgroundFor(AlmanacEntries.Page openPage) {
        return openPage == AlmanacEntries.Page.ZOMBIES ? BACKGROUND_ZOMBIES : BACKGROUND_PLANTS;
    }

    private Identifier cardArt(AlmanacEntries.Page openPage) {
        return openPage == AlmanacEntries.Page.ZOMBIES ? CARD_ZOMBIE : CARD_PLANT;
    }

    private static float artWidth(AlmanacEntries.Page openPage) {
        return openPage == AlmanacEntries.Page.ZOMBIES ? ZOMBIE_ART_W : PLANT_ART_W;
    }

    private static float artHeight(AlmanacEntries.Page openPage) {
        return openPage == AlmanacEntries.Page.ZOMBIES ? ZOMBIE_ART_H : PLANT_ART_H;
    }

    private static float[] imageWindow(AlmanacEntries.Page openPage) {
        return openPage == AlmanacEntries.Page.ZOMBIES
                ? new float[]{ZOMBIE_IMAGE_X, ZOMBIE_IMAGE_Y, ZOMBIE_IMAGE_W, ZOMBIE_IMAGE_H}
                : new float[]{PLANT_IMAGE_X, PLANT_IMAGE_Y, PLANT_IMAGE_W, PLANT_IMAGE_H};
    }

    private static float[] textWindow(AlmanacEntries.Page openPage) {
        return openPage == AlmanacEntries.Page.ZOMBIES
                ? new float[]{ZOMBIE_TEXT_X, ZOMBIE_TEXT_Y, ZOMBIE_TEXT_W, ZOMBIE_TEXT_H}
                : new float[]{PLANT_TEXT_X, PLANT_TEXT_Y, PLANT_TEXT_W, PLANT_TEXT_H};
    }

    private float[] inkFor(AlmanacEntries.Page openPage) {
        return openPage == AlmanacEntries.Page.ZOMBIES ? ZOMBIE_GREEN : PLANT_GOLD;
    }

    /** The page's own name for the title band, e.g. "Almanac - Plants". */
    private String pageTitle(AlmanacEntries.Page openPage) {
        String key = switch (openPage) {
            case PLANTS -> "gui.pvzce.almanac.page_title_plants";
            case ZOMBIES -> "gui.pvzce.almanac.page_title_zombies";
            case RESOURCES -> "gui.pvzce.almanac.page_title_resources";
        };
        return GuiLang.raw(key, "Almanac - " + openPage.name());
    }

    // ------------------------------------------------------------------------------------------
    // Painters
    // ------------------------------------------------------------------------------------------

    private void drawBackground(Identifier texture) {
        client.drawTexture(texture, sx(0F), sy(NATIVE_HEIGHT), sw(NATIVE_WIDTH),
                sw(NATIVE_HEIGHT), -1F, 1F, 1F, 1F, 1F);
    }

    /**
     * The stone plate the page's name is printed on.
     *
     * <p>Drawn rather than taken from the background: both entry backgrounds do carry a blank
     * plate, but the index background's bar is a different colour and size, and one painter for
     * all three pages is what keeps the title in the same place everywhere.
     */
    private void drawTitlePlate(String text, float[] ink) {
        client.drawSolid(sx(TITLE_X - 3F), sy(TITLE_Y + TITLE_H + 3F), sw(TITLE_W + 6F),
                sw(TITLE_H + 6F), -0.6F, PLATE_EDGE[0], PLATE_EDGE[1], PLATE_EDGE[2], 0.85F);
        client.drawSolid(sx(TITLE_X), sy(TITLE_Y + TITLE_H), sw(TITLE_W), sw(TITLE_H), -0.6F,
                PLATE_FILL[0], PLATE_FILL[1], PLATE_FILL[2], 0.72F);
        drawLineCentredIn(TITLE_X, TITLE_W, text, TITLE_BASELINE, TITLE_SCALE, ink);
    }

    /** One of the two card frames, drawn at its own size. */
    private void drawCardArt(AlmanacEntries.Page openPage, float x, float y, float w, float h) {
        client.drawTexture(cardArt(openPage), sx(x), sy(y + h), sw(w), sw(h), 0F, 1F, 1F, 1F, 1F);
    }

    /**
     * An entry's art in a box.
     *
     * <p>Static, as the original's is: this project has no pre-rendered almanac frames, and the
     * alternatives are worse - animating needs the world projection, which resets the GUI view the
     * page's text is drawn with, and baking frames is a toolchain of its own. A plant uses its seed
     * packet's 1:1 icon, which is the same art the chooser and the in-game bar draw; a zombie uses
     * its animation's largest part, which is what the editor's palette does.
     */
    private void drawPreview(AlmanacEntries.Page openPage, Identifier id, float x, float y,
                             float w, float h) {
        if (id == null || w <= 0F || h <= 0F) {
            return;
        }
        Identifier texture = previewTexture(openPage, id);
        if (texture == null) {
            return;
        }
        drawSpriteFitted(texture, x, y, w, h, 0.2F);
    }

    /**
     * The texture a static preview of an entry draws.
     *
     * <p>Plants use their seed packet icon, which is the 1:1 art the chooser and the in-game bar
     * draw and is exactly what the original prints on a plant's page. A zombie has no single-body
     * sprite - its art is the parts of the rig, one PNG per limb - so it falls back to the
     * animation's largest visible part, which is its body; the animated detail panel is where a
     * zombie is drawn whole.
     */
    private Identifier previewTexture(AlmanacEntries.Page openPage, Identifier id) {
        Identifier texture = null;
        if (openPage == AlmanacEntries.Page.PLANTS) {
            // The seed packet's 1:1 icon: the same art the chooser and the in-game bar draw.
            var slot = SlotResolver.resolve(id).orElse(null);
            texture = slot == null ? null : slot.icon().orElse(null);
        } else if (openPage == AlmanacEntries.Page.RESOURCES) {
            var resource = BuiltInRegistries.RESOURCES.get(id);
            texture = resource == null ? null : resource.icon();
        }
        // A zombie deliberately has no texture path here: its art is one PNG per limb, so every
        // candidate is either a directory or a single body part. Its caller draws the rig instead
        // (and leaves the card empty when there is no rig to draw).
        return texture != null && client.hasTexture(texture) ? texture : null;
    }

    /**
     * Draws a sprite inside a box, at its own aspect ratio and centred.
     *
     * <p>Not stretched to the box: a seed packet icon is square and the boxes on this page are not,
     * and the first version stretched them - a Peashooter came out half as wide as it is tall.
     * {@code coverGround} puts the plot behind the sprite, which is what the original does.
     */
    private void drawSpriteFitted(Identifier texture, float x, float y, float w, float h, float z) {
        // Probed and caught: `hasTexture` answering yes is not a promise that the bytes decode
        // (a directory listing, a truncated pack file), and the texture manager throws rather than
        // returning null. A missing picture must not take the render loop down with it.
        if (texture == null || !client.hasTexture(texture)) {
            return;
        }
        com.pvzce.client.renderer.texture.Texture tex;
        try {
            tex = client.textures().getOrLoad(texture);
        } catch (RuntimeException e) {
            return;
        }
        float texW = Math.max(1, tex.width());
        float texH = Math.max(1, tex.height());
        float fit = Math.min(w / texW, h / texH);
        float drawW = texW * fit;
        float drawH = texH * fit;
        client.drawTexture(texture, sx(x + (w - drawW) / 2F), sy(y + (h - drawH) / 2F + drawH),
                sw(drawW), sw(drawH), z, 1F, 1F, 1F, 1F);
    }

    /** The grass the entries stand on, tiled to fill a window. */
    private void drawGround(float x, float y, float w, float h) {
        if (!client.hasTexture(GROUND_DAY)) {
            return;
        }
        var tex = client.textures().getOrLoad(GROUND_DAY);
        float tile = 40F;
        for (float ty = y; ty < y + h; ty += tile) {
            for (float tx = x; tx < x + w; tx += tile) {
                float tw = Math.min(tile, x + w - tx);
                float th = Math.min(tile, y + h - ty);
                client.drawTextureRegion(GROUND_DAY, 0F, 0F, tw / tile, th / tile,
                        sx(tx), sy(ty + th), sw(tw), sw(th), -0.05F, 1F, 1F, 1F, 1F);
            }
        }
        // A soft dark rim so the plot reads as a window rather than as a hole in the card. The
        // texture is only used to size the tile above; the rim is flat.
        client.drawSolid(sx(x), sy(y + h), sw(w), sw(2F), 0.1F, 0F, 0F, 0F, 0.25F);
    }

    /** The two footer buttons. */
    private void drawFooter(boolean showIndex) {
        if (showIndex) {
            drawTexturedButton(BUTTON_INDEX, BUTTON_INDEX_LIT, INDEX_BUTTON_X, FOOTER_Y,
                    INDEX_BUTTON_W, FOOTER_H,
                    GuiLang.raw("gui.pvzce.almanac.index_button", "Almanac Index"),
                    BLACK, this::showIndex);
        }
        drawTexturedButton(BUTTON_CLOSE, BUTTON_CLOSE_LIT, CLOSE_BUTTON_X, FOOTER_Y,
                CLOSE_BUTTON_W, FOOTER_H,
                GuiLang.raw("gui.pvzce.almanac.close", "Close"), BLACK, this::requestClose);
    }

    private void drawTexturedButton(Identifier texture, Identifier lit, float x, float y, float w,
                                    float h, String label, float[] ink, Runnable action) {
        boolean hovered = hovering(x, y, w, h);
        client.drawTexture(hovered ? lit : texture, sx(x), sy(y + h), sw(w), sw(h), 0.4F,
                1F, 1F, 1F, 1F);
        drawLineCentredIn(x, w, label, y + 9F, BUTTON_SCALE, ink);
        hits.add(new Hit(x, y, w, h, action));
    }

    /** One of the index's two "view" buttons: a small plate of the page's own colour. */
    private void viewButton(String label, AlmanacEntries.Page target, float x, float y,
                            float[] accent, float[] shadow) {
        float w = VIEW_BUTTON_W;
        float h = VIEW_BUTTON_H;
        boolean hovered = hovering(x, y, w, h);
        client.drawSolid(sx(x), sy(y + h), sw(w), sw(h), 0.3F, shadow[0], shadow[1], shadow[2], 1F);
        client.drawSolid(sx(x + 3F), sy(y + h - 3F), sw(w - 6F), sw(h - 6F), 0.3F,
                hovered ? 0.55F : 0.30F, hovered ? 0.42F : 0.22F, hovered ? 0.20F : 0.10F, 1F);
        drawLineCentredIn(x, w, label, y + 8F, VIEW_LABEL_SCALE, accent);
        hits.add(new Hit(x, y, w, h, () -> openPage(target)));
    }

    private boolean hovering(float x, float y, float w, float h) {
        var window = client.window();
        float nx = nativeX(client.guiMouseX(window.cursorX()));
        float ny = nativeY(client.guiMouseY(window.cursorY()));
        return nx >= x && nx <= x + w && ny >= y && ny <= y + h;
    }

    // ------------------------------------------------------------------------------------------
    // Text: positions in native units, sizes in the project's GUI-scale band
    // ------------------------------------------------------------------------------------------

    /**
     * A line drawn from {@code nativeX} rightward.
     *
     * <p>Coordinates are native units ({@link #sx}/{@link #sy}); <em>font scales are not</em> - they
     * are the same 0.7..1.5 band every other screen uses. Converting a font size from native units
     * is the mistake that cost the most time in this file; see the pitfalls list.
     */
    private void drawTextLeft(String text, float nativeX, float nativeY, float fontScale,
                              float[] ink) {
        client.fonts().body().draw(text, sx(nativeX), sy(nativeY), fontScale,
                ink[0], ink[1], ink[2], 1F);
    }

    private void drawLineCentredIn(float boxX, float boxW, String text, float nativeBaseline,
                                   float fontScale, float[] ink) {
        client.fonts().body().drawCentered(text, sx(boxX + boxW / 2F), sy(nativeBaseline),
                fontScale, ink[0], ink[1], ink[2], 1F);
    }

    /** Text width in native units, for the callers that lay out in native space. */
    private float width(String text, float fontScale) {
        return client.fonts().body().width(text, fontScale) / Math.max(0.01F, scale);
    }

    /**
     * Wraps {@code text} into a native-space box, returning the y below the last line.
     *
     * <p>{@code bottom} is a hard floor: a line whose baseline would fall past it is not drawn. That
     * is how every box on this screen keeps its text inside its own frame, rather than with a
     * scissor - see {@link #renderDetailText} for why the scissor cannot be trusted here.
     */
    private float drawWrapped(String text, float x, float y, float width, float fontScale,
                              float[] ink, float bottom) {
        if (text == null || text.isBlank()) {
            return y;
        }
        List<String> lines = client.fonts().body().wrapLines(text, sw(width), fontScale);
        float lineHeight = client.fonts().body().lineHeight(fontScale) / Math.max(0.01F, scale);
        float cursor = y;
        for (String line : lines) {
            if (cursor < bottom) {
                break;
            }
            client.fonts().body().draw(line, sx(x), sy(cursor), fontScale, ink[0], ink[1], ink[2], 1F);
            cursor -= lineHeight;
        }
        return cursor;
    }

    // ------------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------------

    @Override
    protected void onMouseClicked(double guiX, double guiY, int button) {
        if (button != 0) {
            return;
        }
        float nx = nativeX(guiX);
        float ny = nativeY(guiY);
        // Topmost first: the footer and the "view" buttons are added after the shelf, so a click
        // on a button that overlaps a card must not fall through to the card.
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.contains(nx, ny)) {
                hit.action().run();
                return;
            }
        }
    }

    @Override
    protected void onMouseScrolled(double guiX, double guiY, double amount) {
        // The shelf has no scrolling: it is sized for every entry a page can hold the way the
        // original's is. A page a mod makes longer than the shelf is walkable with the arrow keys.
        if (page >= 0) {
            select(amount > 0 ? 1 : -1);
        }
    }

    /**
     * While an entry page is open the whole shelf is a vertical scroll region: one step of finger
     * travel moves the selection by one entry, which is what the wheel and the arrow keys already do.
     *
     * <p>{@link ScrollRegion.Swipe#MIRRORS_WHEEL}: there is no content to drag here - the selection
     * steps through the catalogue - so "finger up = wheel up = next entry" is the only predictable
     * answer. Without the region a swipe over the shelf would press whichever card it started on.
     */
    @Override
    protected ScrollRegion onScrollRegionAt(double guiX, double guiY) {
        if (page < 0) {
            return null;
        }
        return ScrollRegion.mirrorsWheel(ScrollRegion.Axis.VERTICAL, ScrollRegion.DEFAULT_STEP);
    }

    @Override
    public void keyPressed(int key) {
        if (page >= 0) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT || key == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN) {
                select(1);
                return;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT || key == org.lwjgl.glfw.GLFW.GLFW_KEY_UP) {
                select(-1);
                return;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) {
                showIndex();
                return;
            }
        }
        super.keyPressed(key);
    }

    private void select(int delta) {
        AlmanacEntries.Catalogue catalogue = current();
        if (catalogue == null || catalogue.size() == 0) {
            return;
        }
        selected = Math.floorMod(selected + delta, catalogue.size());
    }

    // ------------------------------------------------------------------------------------------
    // Read-only accessors, for the smoke driver and the tests
    // ------------------------------------------------------------------------------------------

    /** {@code -1} on the index, otherwise the page being read. */
    public int pageIndex() {
        return page;
    }

    /** The entry the detail card is showing, or {@code null} on the index. */
    public Identifier currentEntry() {
        AlmanacEntries.Catalogue catalogue = current();
        return catalogue == null ? null : catalogue.at(selected);
    }

    /** How many entries the open page holds, or {@code 0} on the index. */
    public int entryCount() {
        AlmanacEntries.Catalogue catalogue = current();
        return catalogue == null ? 0 : catalogue.size();
    }
}
