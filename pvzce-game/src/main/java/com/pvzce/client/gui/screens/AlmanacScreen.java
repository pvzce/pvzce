package com.pvzce.client.gui.screens;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.almanac.AlmanacEntries;
import com.pvzce.client.renderer.EntityTextures;
import com.pvzce.client.renderer.EntityVisuals;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.core.EntityArt;
import com.pvzce.common.core.SlotResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * The almanac: the book that replaced the backpack.
 *
 * <p>Three pages - plants, zombies, resources - reached from an index, exactly as the original's
 * Suburban Almanac is laid out, on the original's own art (the 800x600 backgrounds and the two
 * card frames converted from {@code refer/im7/images}). The text is the original's too, read from
 * the language files: {@code plant.pvzce.pea_shooter.desc} is the line printed on the card face
 * and {@code .flavor} is the bio underneath. Numbers are not in the language file - sun cost,
 * recharge, health, speed and bite damage come from the definitions the simulation actually uses,
 * so the book can never advertise a value the game does not.
 *
 * <p><b>Why it replaced the backpack.</b> The old screen listed cards the player owns and greyed
 * out the ones they do not, which answers "what can I bring". The almanac answers that too (an
 * unlocked plant's card is drawn at full brightness, an unlocked one is a silhouette) and also the
 * question underneath it - "what <em>is</em> this thing" - which the old screen had nowhere to
 * put. It is read-only either way: nothing here changes the profile.
 *
 * <p><b>Layout.</b> Everything is measured against the art's own 800x600 canvas and scaled by one
 * factor, so the page cannot come apart at a different window size: the card frames, their text
 * panels and the fonts all move together. The pages run in the original's order
 * ({@link AlmanacEntries}), arrow keys and the side buttons turn them, and the counter between the
 * arrows says where the reader is.
 */
public final class AlmanacScreen extends Screen {
    /**
     * Font scales, as every other screen writes them: a scale of 1 draws a hanzi about 14 GUI
     * units tall, and the call sites in this project sit between 0.6 and 2.0.
     *
     * <p>These are <em>not</em> native units and must not be scaled by the page's own factor: the
     * font renderer already multiplies by the window's GUI scale. Two attempts to be clever here -
     * dividing a native-unit size by the em, then by the em and the page scale - both produced
     * text several times too large, because the page's factor is already applied to the GUI
     * coordinates it is drawn at.
     */
    private static final float TITLE_SCALE = 1.5F;
    private static final float INDEX_LABEL_SCALE = 1.5F;
    private static final float INDEX_COUNT_SCALE = 0.9F;
    private static final float PAGE_TITLE_SCALE = 1.2F;
    private static final float PAGE_NUMBER_SCALE = 0.9F;
    private static final float CARD_LINE_SCALE = 0.95F;
    private static final float LOCKED_SCALE = 1.1F;
    private static final float STATS_SCALE = 1.0F;
    private static final float FLAVOR_SCALE = 0.9F;
    private static final float FOOTER_SCALE = 0.8F;

    /** The gold the title and the index labels are printed in. */
    private static final float[] GOLD = {1F, 0.94F, 0.4F, 1F};
    /** Muted grey-gold, for a count or a page number. */
    private static final float[] MUTED_GOLD = {0.86F, 0.80F, 0.62F, 0.95F};
    /** The footer buttons and the entry title keep their own white. */
    private static final float[] BUTTON_WHITE = {1F, 1F, 1F, 1F};

    /** The canvas the art was authored on; every measurement below is in these units. */
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
    private static final Identifier MISSING_TEXTURE = EntityArt.MISSING_TEXTURE;

    /**
     * Card geometry, in native units, taken from the art rather than invented.
     *
     * <p>The frame is drawn at its own pixel size (scaled by the page's one factor) with its two
     * windows filled by our own content. It is <em>not</em> nine-sliced to a shape the page would
     * prefer: the first attempt did that and the painted middle smeared, because the art is a
     * picture of a card rather than a border kit. So the numbers below are read off the two PNGs:
     * the picture window and the text panel sit exactly where the artist painted them, and the
     * card keeps the proportions it was drawn with.
     */
    private static final float PLANT_CARD_W = 324F;
    private static final float PLANT_CARD_H = 484F;
    private static final float PLANT_IMAGE_X = 71F;
    private static final float PLANT_IMAGE_Y = 32F;
    private static final float PLANT_IMAGE_W = 181F;
    private static final float PLANT_IMAGE_H = 133F;
    private static final float PLANT_TEXT_X = 24F;
    private static final float PLANT_TEXT_Y = 223F;
    private static final float PLANT_TEXT_W = 264F;
    private static final float PLANT_TEXT_H = 231F;

    private static final float ZOMBIE_CARD_W = 324F;
    private static final float ZOMBIE_CARD_H = 497F;
    private static final float ZOMBIE_IMAGE_X = 72F;
    private static final float ZOMBIE_IMAGE_Y = 58F;
    private static final float ZOMBIE_IMAGE_W = 183F;
    private static final float ZOMBIE_IMAGE_H = 170F;
    private static final float ZOMBIE_TEXT_X = 28F;
    private static final float ZOMBIE_TEXT_Y = 298F;
    private static final float ZOMBIE_TEXT_W = 264F;
    private static final float ZOMBIE_TEXT_H = 162F;

    /**
     * Where the card sits on the page, and how big it is drawn.
     *
     * <p>The card is on the left and everything else is to its right and under it: the frame is
     * 324x497 art pixels, so a card that fills the page would leave no room for the text the book
     * exists to show.
     */
    private static final float CARD_X = 74F;
    private static final float CARD_Y = 118F;
    private static final float CARD_DRAW_SCALE = 0.82F;

    /** The numbers, in the right-hand column, level with the card's picture window. */
    private static final float STATS_X = 400F;
    private static final float STATS_Y = 236F;
    private static final float STATS_W = 200F;

    /** The bio, under the card, where the page has room for a long paragraph. */
    private static final float FLAVOR_X = 400F;
    private static final float FLAVOR_Y = 300F;
    private static final float FLAVOR_W = 350F;
    private static final float FLAVOR_H = 210F;

    /**
     * How much of the world a card window shows, in cells.
     *
     * <p>Under one cell, so the entity fills the window: a plant is drawn about 0.5 cells wide and
     * a zombie about 0.7, and a window showing a whole cell would leave both rattling around in
     * the middle of an empty square.
     */
    private static final float PREVIEW_CELLS = 0.85F;

    /** The page arrows, in the margins either side of the card. */
    private static final float ARROW_W = 30F;
    private static final float ARROW_H = 70F;

    /**
     * The index's rows: the three pages, one per row.
     *
     * <p>A row rather than a card: three pages do not fill the original's two-by-two, and a card
     * squeezed into a quarter of its height is a smear. Each row draws the page's own frame at
     * {@link #CARD_DRAW_SCALE} beside its name, which reads as a shelf of cards.
     */
    private static final float INDEX_X = 104F;
    private static final float INDEX_W = 592F;
    private static final float INDEX_H = 132F;
    private static final float INDEX_GAP = 10F;
    private static final float INDEX_TOP = 120F;
    /**
     * How big the card frame is drawn on the index, in native units per art pixel.
     *
     * <p>Small on purpose: the index background already paints a slate board down the right-hand
     * side of the page (it is in the art, not drawn by us), so the rows and their labels live in
     * the left column and the board stays visible as the page's own decoration.
     */
    private static final float INDEX_CARD_SCALE = 0.25F;

    /** The entry page's title plate, measured on {@code plant_background} and shared by both. */
    /**
     * The entry title's plate, measured on {@code plant_background}, and the baseline the title
     * is printed on inside it.
     *
     * <p>The baseline matters: the plate is 22 units tall, so a 1.2-scale line (about 17 units of
     * ink) has to start within 5 units of the plate's top. {@link #TITLE_BASELINE} is the plate's
     * top plus an ascent's worth, which is what centres the glyphs in the band rather than
     * balancing them on its top edge.
     */
    private static final float TITLE_X = 71F;
    private static final float TITLE_Y = 78F;
    private static final float TITLE_W = 646F;
    private static final float TITLE_H = 22F;
    private static final float TITLE_BASELINE = TITLE_Y + 24F;

    /** Ink colours, sampled from the art: the plant card is warm, the zombie card is not. */
    private static final float[] PLANT_INK = {0.28F, 0.13F, 0.04F};
    private static final float[] ZOMBIE_INK = {0.10F, 0.10F, 0.17F};

    private final List<AlmanacEntries.Catalogue> catalogues = new ArrayList<>();
    /** {@code -1} shows the index; anything else is an index into {@link #catalogues}. */
    private int page = -1;
    private int entry;
    /**
     * A page asked for before {@link #init()} ran, applied by it.
     *
     * <p>A screen is initialized lazily - on the first frame it is rendered - so anything that
     * wants to open the book somewhere other than its first page (the smoke driver's screenshot
     * runs, and anything else that constructs rather than clicks) has to say so without the state
     * being thrown away a frame later.
     */
    private AlmanacEntries.Page wanted;
    /** An entry asked for alongside {@link #wanted}; {@code -1} means "the first one". */
    private int pendingEntry = -1;

    private float scale = 1F;
    private float originX;
    private float originY;

    private ClientEntity preview;
    /**
     * One preview per index card, in the order the index lists them.
     *
     * <p>The index draws each page's first entry as its own icon, and a still of a plant is not
     * what the plant looks like - so the index animates too. Three entities at most, ticked only
     * while the index is the page on screen.
     */
    private final List<ClientEntity> indexPreviews = new ArrayList<>();
    /** The rects the current frame was drawn with, so a click can be tested against them. */
    private final List<Hit> hits = new ArrayList<>();

    /** A clickable rect in native units, with what it does. */
    private record Hit(float x, float y, float w, float h, Runnable action) {
        boolean contains(float nx, float ny) {
            return nx >= x && nx <= x + w && ny >= y && ny <= y + h;
        }
    }

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
        // The profile travels with the level list, and the almanac draws lock state from it.
        // Without this, opening the book as the first thing after launch showed every plant as a
        // silhouette - the same trap the backpack screen documented before it.
        if (client.connection() != null) {
            client.connection().send(new com.pvzce.common.network.packet.RequestLevelListC2S(
                    client.currentWorld()));
        }
        catalogues.clear();
        catalogues.addAll(AlmanacEntries.all());
        rebuildIndexPreviews();
        if (wanted != null) {
            AlmanacEntries.Page target = wanted;
            wanted = null;
            openPage(target);
            if (pendingEntry >= 0) {
                AlmanacEntries.Catalogue opened = current();
                entry = opened == null ? 0 : Math.floorMod(pendingEntry, Math.max(1, opened.size()));
            }
            pendingEntry = -1;
        }
        rebuildPreview();
    }

    @Override
    protected void onRemoved() {
        releasePreview();
        releaseIndexPreviews();
    }

    /** Builds the index's per-card previews; one per catalogue, in order. */
    private void rebuildIndexPreviews() {
        releaseIndexPreviews();
        if (client.animations() == null) {
            return;
        }
        int index = 0;
        for (AlmanacEntries.Catalogue catalogue : catalogues) {
            Identifier id = catalogue.at(0);
            if (id == null) {
                continue;
            }
            ClientEntity entity = new ClientEntity(-100 - index, catalogue.page().entityKind(),
                    id.toString(), 0.5F, 0.5F, 100, 1, "idle", 0F, "");
            entity.attachAnimationManager(client.animations());
            indexPreviews.add(entity);
            index++;
        }
    }

    private void releaseIndexPreviews() {
        if (client.animations() != null) {
            for (ClientEntity entity : indexPreviews) {
                client.animations().release(entity);
            }
        }
        indexPreviews.clear();
    }

    /** The page currently being read, or {@code null} on the index. */
    private AlmanacEntries.Catalogue current() {
        return page < 0 || page >= catalogues.size() ? null : catalogues.get(page);
    }

    /** Back to the index, whether or not the screen has been initialized yet. */
    public void showIndex() {
        if (catalogues.isEmpty()) {
            wanted = null;
            pendingEntry = -1;
            page = -1;
            entry = 0;
            return;
        }
        page = -1;
        entry = 0;
        releasePreview();
    }

    private void openPage(AlmanacEntries.Page wanted) {
        for (int i = 0; i < catalogues.size(); i++) {
            if (catalogues.get(i).page() == wanted) {
                page = i;
                entry = 0;
                rebuildPreview();
                return;
            }
        }
    }

    /** Turns the page by {@code delta} entries, wrapping; the index has nothing to turn. */
    private void turn(int delta) {
        AlmanacEntries.Catalogue catalogue = current();
        if (catalogue == null || catalogue.size() == 0) {
            return;
        }
        entry = Math.floorMod(entry + delta, catalogue.size());
        rebuildPreview();
    }

    /**
     * Rebuilds the animated preview of the entry on screen.
     *
     * <p>The card window plays the entity's own idle animation rather than showing a still: the
     * art is already in the game, the editor's canvas has drawn preview entities this way since it
     * was written, and a book that showed a plant mid-bloom exactly as it looks on the lawn is
     * more use than a photograph of it. A resource or a mod's content with no animation resource
     * falls back to its sprite.
     */
    private void rebuildPreview() {
        releasePreview();
        AlmanacEntries.Catalogue catalogue = current();
        if (catalogue == null || client.animations() == null) {
            return;
        }
        Identifier id = catalogue.at(entry);
        if (id == null) {
            return;
        }
        preview = new ClientEntity(-1, catalogue.page().entityKind(), id.toString(),
                0.5F, 0.5F, 100, 1, "idle", 0F, "");
        preview.attachAnimationManager(client.animations());
    }

    private void releasePreview() {
        if (preview != null && client.animations() != null) {
            client.animations().release(preview);
        }
        preview = null;
    }

    @Override
    public void tick() {
        if (preview != null) {
            preview.update(0.5F, 0.5F, 100, "idle", 0F);
            preview.playAnimation("idle");
        }
        for (ClientEntity entity : indexPreviews) {
            entity.update(0.5F, 0.5F, 100, "idle", 0F);
            entity.playAnimation("idle");
        }
    }

    @Override
    public void render() {
        client.beginGuiView();
        computeLayout();
        hits.clear();

        // The letterbox: the book is contained, so a window that is not 4:3 leaves bars, and a
        // warm dark brown reads as the desk it is lying on rather than as a missing image.
        client.drawSolid(0, 0, client.guiWidth(), client.guiHeight(), -2F, 0.09F, 0.05F, 0.03F, 1F);
        if (page < 0) {
            renderIndex();
        } else {
            renderEntry();
        }
    }

    /**
     * One scale for everything, and it is a <em>contain</em> fit.
     *
     * <p>Cover-fitting the page was the first attempt and it was wrong: the book's own frame is
     * inside the 800x600 image, so filling a 16:9 window crops 75 native units off the top and
     * bottom - which is the title plate and half of the footer buttons. Letterboxing instead keeps
     * the whole page, which is what a book wants; the bars are painted dark so they read as the
     * desk the book is lying on rather than as a bug.
     */
    private void computeLayout() {
        scale = Math.min(client.guiWidth() / NATIVE_WIDTH, client.guiHeight() / NATIVE_HEIGHT);
        originX = (client.guiWidth() - NATIVE_WIDTH * scale) / 2F;
        originY = (client.guiHeight() - NATIVE_HEIGHT * scale) / 2F;
    }

    /** A native-space rect in GUI pixels. */
    private float sx(float nativeX) {
        return originX + nativeX * scale;
    }

    private float sy(float nativeY) {
        // Native space grows downward from the top of the art; GUI space grows upward from the
        // bottom of the window. One flip, here, so nothing else in the file has to think about it.
        return originY + (NATIVE_HEIGHT - nativeY) * scale;
    }

    private float sw(float nativeWidth) {
        return nativeWidth * scale;
    }

    /** Converts a GUI-space click into native space; the inverse of {@link #sx}/{@link #sy}. */
    private float nativeX(double guiX) {
        return (float) ((guiX - originX) / scale);
    }

    private float nativeY(double guiY) {
        return NATIVE_HEIGHT - (float) ((guiY - originY) / scale);
    }

    private void drawBackground(Identifier texture) {
        client.drawTexture(texture, sx(0F), sy(NATIVE_HEIGHT), sw(NATIVE_WIDTH),
                sw(NATIVE_HEIGHT), -1F, 1F, 1F, 1F, 1F);
    }

    // ------------------------------------------------------------------------------------------
    // Index
    // ------------------------------------------------------------------------------------------

    /**
     * The index: one card per page, in the original's two-by-two arrangement.
     *
     * <p>The fourth cell is the original's second place-holder (it has "plant or zombie" and an
     * empty frame beside it) and stays empty here - the book has exactly three pages, and an
     * empty frame is an honest way to say so.
     */
    private void renderIndex() {
        drawBackground(BACKGROUND_INDEX);
        drawLineCentred(GuiLang.raw("gui.pvzce.almanac.title", "Almanac"), 70F, TITLE_SCALE, GOLD);

        for (int i = 0; i < catalogues.size() && i < 3; i++) {
            drawIndexRow(catalogues.get(i), INDEX_X, INDEX_TOP + i * (INDEX_H + INDEX_GAP));
        }

        drawFooterButtons(true);
    }

    /**
     * One index row: the page's frame on the left with its first entry in the picture window, and
     * the page's name beside it.
     *
     * <p>The frame is drawn at {@link #INDEX_CARD_SCALE} - small, but at its own proportions, so
     * the picture window is a window rather than a smear. The name goes on the row's paper to the
     * right of the card, which is the only place on this background with room for it.
     */
    private void drawIndexRow(AlmanacEntries.Catalogue catalogue, float x, float y) {
        float artW = cardWidth(catalogue.page()) * INDEX_CARD_SCALE;
        float artH = cardHeight(catalogue.page()) * INDEX_CARD_SCALE;
        // Vertically centred in the row.
        float artY = y + (INDEX_H - artH) / 2F;
        drawCardArt(catalogue.page(), x, artY, INDEX_CARD_SCALE);

        if (catalogue.size() > 0) {
            float[] window = imageWindow(catalogue.page());
            drawAnimated(catalogue.at(0), catalogue.page().entityKind(), indexPreviewFor(catalogue),
                    x + window[0] * INDEX_CARD_SCALE, artY + window[1] * INDEX_CARD_SCALE,
                    window[2] * INDEX_CARD_SCALE, window[3] * INDEX_CARD_SCALE);
        }

        float labelX = x + artW + 44F;
        drawTextLeft(GuiLang.raw(catalogue.page().labelKey(), catalogue.page().name()),
                labelX, y + INDEX_H * 0.56F, INDEX_LABEL_SCALE, GOLD);
        drawTextLeft(catalogue.size() + "", labelX + 2F, y + INDEX_H * 0.26F, INDEX_COUNT_SCALE, MUTED_GOLD);

        hits.add(new Hit(x, y, INDEX_W, INDEX_H, () -> openPage(catalogue.page())));
    }

    /** The card art's own size for a page. */
    private static float cardWidth(AlmanacEntries.Page page) {
        return page == AlmanacEntries.Page.ZOMBIES ? ZOMBIE_CARD_W : PLANT_CARD_W;
    }

    private static float cardHeight(AlmanacEntries.Page page) {
        return page == AlmanacEntries.Page.ZOMBIES ? ZOMBIE_CARD_H : PLANT_CARD_H;
    }

    private static Identifier cardArt(AlmanacEntries.Page page) {
        return page == AlmanacEntries.Page.ZOMBIES ? CARD_ZOMBIE : CARD_PLANT;
    }

    /** {@code {x, y, w, h}} of a page's picture window, in the card art's own pixels. */
    private static float[] imageWindow(AlmanacEntries.Page page) {
        return page == AlmanacEntries.Page.ZOMBIES
                ? new float[]{ZOMBIE_IMAGE_X, ZOMBIE_IMAGE_Y, ZOMBIE_IMAGE_W, ZOMBIE_IMAGE_H}
                : new float[]{PLANT_IMAGE_X, PLANT_IMAGE_Y, PLANT_IMAGE_W, PLANT_IMAGE_H};
    }

    /** {@code {x, y, w, h}} of a page's text panel, in the card art's own pixels. */
    private static float[] textWindow(AlmanacEntries.Page page) {
        return page == AlmanacEntries.Page.ZOMBIES
                ? new float[]{ZOMBIE_TEXT_X, ZOMBIE_TEXT_Y, ZOMBIE_TEXT_W, ZOMBIE_TEXT_H}
                : new float[]{PLANT_TEXT_X, PLANT_TEXT_Y, PLANT_TEXT_W, PLANT_TEXT_H};
    }

    // ------------------------------------------------------------------------------------------
    // Entry page
    // ------------------------------------------------------------------------------------------

    private void renderEntry() {
        AlmanacEntries.Catalogue catalogue = current();
        if (catalogue == null) {
            showIndex();
            return;
        }
        drawBackground(backgroundFor(catalogue.page()));
        if (catalogue.size() == 0) {
            drawLineCentred(GuiLang.raw("gui.pvzce.almanac.empty", "Nothing here yet"),
                    300F, PAGE_TITLE_SCALE, MUTED_GOLD);
            drawFooterButtons(false);
            return;
        }

        Identifier id = catalogue.at(entry);
        drawTitle(GuiLang.name(catalogue.page().category(), id));

        float artX = CARD_X;
        float artW = cardWidth(catalogue.page()) * CARD_DRAW_SCALE;
        float artH = cardHeight(catalogue.page()) * CARD_DRAW_SCALE;
        drawCardArt(catalogue.page(), artX, CARD_Y, CARD_DRAW_SCALE);

        float[] window = imageWindow(catalogue.page());
        drawAnimated(id, catalogue.page().entityKind(), preview,
                artX + window[0] * CARD_DRAW_SCALE, CARD_Y + window[1] * CARD_DRAW_SCALE,
                window[2] * CARD_DRAW_SCALE, window[3] * CARD_DRAW_SCALE);

        drawCardLine(catalogue.page(), id, artX);
        drawEntryStats(catalogue.page(), id);
        drawFlavor(catalogue.page(), id);

        arrowButton(CARD_X - ARROW_W - 12F, CARD_Y + (artH - ARROW_H) / 2F, ARROW_W, ARROW_H, false,
                () -> turn(-1));
        arrowButton(artX + artW + 12F, CARD_Y + (artH - ARROW_H) / 2F, ARROW_W, ARROW_H, true,
                () -> turn(1));

        String position = GuiLang.raw("gui.pvzce.almanac.page", "{0} / {1}")
                .replace("{0}", String.valueOf(entry + 1))
                .replace("{1}", String.valueOf(catalogue.size()));
        drawLineCentred(position, CARD_Y + artH + 28F, PAGE_NUMBER_SCALE, MUTED_GOLD);

        drawFooterButtons(false);
    }

    /** Draws the frame art at its own proportions, at {@code scale} native units per art pixel. */
    private void drawCardArt(AlmanacEntries.Page page, float x, float y, float scale) {
        client.drawTexture(cardArt(page), sx(x), sy(y + cardHeight(page) * scale),
                sw(cardWidth(page) * scale), sw(cardHeight(page) * scale), 0F, 1F, 1F, 1F, 1F);
    }

    /**
     * The line printed on the card face, inside the art's own text panel.
     *
     * <p>This is the only text that goes on the card: the panel is 264x231 art pixels, which holds
     * the card line comfortably and the bio not at all, and overrunning the painted panel would
     * put text on the wood.
     */
    private void drawCardLine(AlmanacEntries.Page page, Identifier id, float artX) {
        float[] window = textWindow(page);
        float x = artX + window[0] * CARD_DRAW_SCALE;
        float y = CARD_Y + window[1] * CARD_DRAW_SCALE;
        float w = window[2] * CARD_DRAW_SCALE;
        float h = window[3] * CARD_DRAW_SCALE;
        float[] ink = ink();

        if (!isUnlocked(page, id)) {
            drawWrappedCentred(GuiLang.raw("gui.pvzce.almanac.locked", "Not yet unlocked"),
                    x, y + h * 0.52F, w, LOCKED_SCALE, ink, 1F);
            return;
        }
        String desc = GuiLang.contentOr(page.category(), id, "desc",
                GuiLang.raw("gui.pvzce.almanac.empty", "Nothing here yet"));
        drawWrappedCentred(desc, x, y + h - 8F, w, CARD_LINE_SCALE, ink, 1F);
    }

    /**
     * The bio, under the card.
     *
     * <p>Not on the card: the original prints it on the card's back, and this book has one page
     * per entry. Clipped to its own box because a translated bio can be any length, and text
     * running over the page's painted frame looks like a bug rather than like an overflow.
     */
    private void drawFlavor(AlmanacEntries.Page page, Identifier id) {
        if (!isUnlocked(page, id)) {
            drawWrapped(GuiLang.raw("gui.pvzce.almanac.unlock_hint", ""),
                    FLAVOR_X, FLAVOR_Y + FLAVOR_H - 16F, FLAVOR_W, FLAVOR_SCALE, ink(), 0.8F);
            return;
        }
        String flavor = GuiLang.content(page.category(), id, "flavor");
        if (flavor == null || flavor.isBlank()) {
            return;
        }
        float top = FLAVOR_Y + FLAVOR_H - 14F;
        client.clipping().push(sx(FLAVOR_X), sy(FLAVOR_Y), sw(FLAVOR_W), sw(FLAVOR_H));
        try {
            drawWrapped(flavor, FLAVOR_X, top, FLAVOR_W, FLAVOR_SCALE, ink(), 0.9F);
        } finally {
            client.clipping().pop();
        }
    }

    private Identifier backgroundFor(AlmanacEntries.Page page) {
        return switch (page) {
            case PLANTS -> BACKGROUND_PLANTS;
            case ZOMBIES -> BACKGROUND_ZOMBIES;
            case RESOURCES -> BACKGROUND_PLANTS;
        };
    }

    private float[] ink() {
        AlmanacEntries.Catalogue catalogue = current();
        boolean zombies = catalogue != null && catalogue.page() == AlmanacEntries.Page.ZOMBIES;
        return zombies ? ZOMBIE_INK : PLANT_INK;
    }

    /**
     * The entry's name, on the page's own title plate.
     *
     * <p>Not clipped to the plate and not centred inside it: the plate is a shallow 22-unit band
     * and the line it was drawn for is ~17 units of ink, so a 1.2-scale name that is centred in
     * the band has its glyph tops above the band and gets cut. The name is drawn on the plate's
     * baseline and allowed to be a little taller than the artwork's own label was - which is what
     * the original does too, its title being taller than the plate's inner area.
     */
    private void drawTitle(String text) {
        drawLineCentred(text, TITLE_BASELINE, PAGE_TITLE_SCALE, BUTTON_WHITE);
    }

    /**
     * The card's picture window.
     *
     * <p>Rendered through the world projection into the window's own rect - the trick the editor's
     * canvas uses - so an animated entity is drawn by exactly the code that draws it on the lawn,
     * at the size {@link EntityVisuals} gives it, instead of a second implementation that would
     * drift from the first.
     */
    /** The index preview belonging to a catalogue, or {@code null} when it has none. */
    private ClientEntity indexPreviewFor(AlmanacEntries.Catalogue catalogue) {
        int index = catalogues.indexOf(catalogue);
        return index < 0 || index >= indexPreviews.size() ? null : indexPreviews.get(index);
    }

    /**
     * Draws an entry into a window, in native units.
     *
     * <p>The animated path goes through the world projection into the window's own rect - the
     * trick the editor's canvas uses - so an animated entity is drawn by exactly the code that
     * draws it on the lawn, at the size {@link EntityVisuals} gives it, rather than by a second
     * implementation that would drift from the first. The fallback is a flat sprite, which is all
     * a resource or a mod's art-less content has.
     */
    /**
     * Draws an entry into a window, in native units.
     *
     * <p>The animated path goes through the world projection into the window's own rect - the
     * trick the editor's canvas uses - so an animated entity is drawn by exactly the code that
     * draws it on the lawn, rather than by a second implementation that would drift from the
     * first. The world rect is {@link #PREVIEW_CELLS} cells rather than the one cell a plant
     * actually occupies, which zooms the preview in: a Peashooter is about half a cell tall and
     * would otherwise be a speck in a card window.
     */
    private void drawAnimated(Identifier id, String kind, ClientEntity entity,
                              float wx, float wy, float ww, float wh) {
        client.clipping().push(sx(wx), sy(wy + wh), sw(ww), sw(wh));
        try {
            if (entity != null && client.animations() != null) {
                client.beginOverlayWorldView(sx(wx), sy(wy + wh), sw(ww), sw(wh),
                        0F, PREVIEW_CELLS, 0F, PREVIEW_CELLS);
                boolean drawn = client.animations().render(entity);
                // Back to GUI space *before* returning: the world view sets the sprite scale
                // factor as well as the projection, and the caller draws text next. Leaving it
                // set drew the almanac's own labels at world scale - about eight times too big.
                client.beginGuiView();
                if (drawn) {
                    return;
                }
            }
            drawSprite(id, kind, sx(wx), sy(wy + wh), sw(ww), sw(wh));
        } finally {
            client.clipping().pop();
            client.beginGuiView();
        }
    }

    /**
     * The numbers, on the background's right half.
     *
     * <p>Read from the definitions rather than from the language file, so what the book says is
     * what the game does. The original prints type/toughness/cost/recharge there; this project has
     * no "toughness" field (it has real health) and no "type" taxonomy, so it prints what it does
     * have and leaves the rest out rather than inventing a table that would have to be kept in
     * sync by hand.
     */
    private void drawEntryStats(AlmanacEntries.Page page, Identifier id) {
        if (!isUnlocked(page, id)) {
            return;
        }
        float y = STATS_Y;
        for (String line : statLines(page, id)) {
            y = drawWrapped(line, STATS_X, y, STATS_W, STATS_SCALE, ink(), 0.95F) - 10F;
        }
    }

    private List<String> statLines(AlmanacEntries.Page page, Identifier id) {
        List<String> lines = new ArrayList<>();
        String seconds = GuiLang.raw("gui.pvzce.almanac.seconds", "{0}s");
        switch (page) {
            case PLANTS -> {
                var plant = com.pvzce.common.core.BuiltInRegistries.PLANTS.get(id);
                if (plant == null) {
                    return lines;
                }
                int cost = plant.cost().amountOf(com.pvzce.common.PvzceIds.SUN);
                int cooldown = plant.cost().cooldownTicks();
                var slot = com.pvzce.common.core.BuiltInRegistries.SLOT_TYPES.get(id);
                if (slot != null && slot.cost().amountOf(com.pvzce.common.PvzceIds.SUN) > 0) {
                    cost = slot.cost().amountOf(com.pvzce.common.PvzceIds.SUN);
                }
                if (slot != null && slot.cost().cooldownTicks() > 0) {
                    cooldown = slot.cost().cooldownTicks();
                }
                lines.add(GuiLang.raw("gui.pvzce.almanac.stat.cost", "Sun") + "  " + cost);
                if (cooldown > 0) {
                    lines.add(GuiLang.raw("gui.pvzce.almanac.stat.recharge", "Recharge") + "  "
                            + seconds.replace("{0}", trim(cooldown / 60F)));
                }
                lines.add(GuiLang.raw("gui.pvzce.almanac.stat.health", "Health") + "  "
                        + plant.health());
            }
            case ZOMBIES -> {
                var zombie = com.pvzce.common.core.BuiltInRegistries.ZOMBIES.get(id);
                if (zombie == null) {
                    return lines;
                }
                lines.add(GuiLang.raw("gui.pvzce.almanac.stat.health", "Health") + "  "
                        + zombie.health());
                lines.add(GuiLang.raw("gui.pvzce.almanac.stat.speed", "Speed") + "  "
                        + trim(zombie.moveSpeed() * 60F));
                lines.add(GuiLang.raw("gui.pvzce.almanac.stat.bite", "Bite") + "  "
                        + zombie.biteDamage());
            }
            case RESOURCES -> {
                var resource = com.pvzce.common.core.BuiltInRegistries.RESOURCES.get(id);
                if (resource == null) {
                    return lines;
                }
                lines.add(GuiLang.raw("gui.pvzce.almanac.stat.value", "Value") + "  "
                        + resource.defaultValue());
            }
        }
        return lines;
    }

    /** A number with at most one decimal, and no trailing {@code .0}. */
    private static String trim(float value) {
        String text = String.format(java.util.Locale.ROOT, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    /**
     * Whether the player may use this entry.
     *
     * <p>Plants and tools are the backpack's business, exactly as {@link SlotResolver#requiresUnlock}
     * defines it; resources never lock (a level with no sun card could not be played), and zombies
     * never lock either - this project has no record of which zombies a player has met, so the book
     * shows them all rather than pretending to a knowledge it does not have.
     */
    private boolean isUnlocked(AlmanacEntries.Page page, Identifier id) {
        if (page != AlmanacEntries.Page.PLANTS) {
            return true;
        }
        // The card id is the plant id for every plant this project ships, but a pack may grant a
        // plant through a differently named slot, so the card is only assumed when one exists.
        Identifier card = com.pvzce.common.core.BuiltInRegistries.SLOT_TYPES.containsKey(id)
                ? id
                : BuiltInRegistries.SLOT_TYPES.keySet().stream()
                        .filter(slotId -> id.equals(SlotResolver.resolve(slotId)
                                .map(SlotResolver.ResolvedCard::content).orElse(null)))
                        .findFirst()
                        .orElse(id);
        return SlotResolver.owns(client.profile().unlocked(), client.profile().unlockAll(), card);
    }

    // ------------------------------------------------------------------------------------------
    // Shared painters
    // ------------------------------------------------------------------------------------------

    /**
     * A content sprite by its kind, used when there is no animation resource to play.
     *
     * <p>Resources are asked for their declared {@code icon} rather than through
     * {@code EntityArt.sprite}: a resource's authored art lives in {@code textures/resource/},
     * and its {@code texture} field points at a drop sprite under {@code textures/entities/} that
     * does not exist for every denomination. Three of the seven drops (energy bean, redstone,
     * money bag) have no animation either, so this is the path they are always drawn by.
     */
    private void drawSprite(Identifier id, String kind, float x, float y, float w, float h) {
        Identifier texture = null;
        if (com.pvzce.api.entity.EntityKind.RESOURCE.equals(kind)) {
            var resource = BuiltInRegistries.RESOURCES.get(id);
            texture = resource == null ? null : resource.icon();
        }
        if (texture == null) {
            texture = EntityTextures.forEntity(id, kind);
        }
        if (texture == null || !client.hasTexture(texture)) {
            // Nothing to show: a card with an empty window reads as "no art yet", while the
            // shared missing-texture tile reads as a bug - which is what it is, and not what the
            // reader should be told about.
            return;
        }
        client.drawTexture(texture, x, y, w, h, 0.5F, 1F, 1F, 1F, 1F);
    }

    /** The two footer buttons, which the original has on every page. */
    private void drawFooterButtons(boolean onIndex) {
        float height = 26F;
        float y = NATIVE_HEIGHT - 40F;
        float closeW = 89F;
        float indexW = 164F;
        if (onIndex) {
            // On the index only "close" applies; the index button would go where the reader is.
            float x = (NATIVE_WIDTH - closeW) / 2F;
            drawTexturedButton(BUTTON_CLOSE, BUTTON_CLOSE_LIT, x, y, closeW, height,
                    GuiLang.raw("gui.pvzce.almanac.close", "Close"), () -> requestClose());
            return;
        }
        float gap = 240F;
        float left = (NATIVE_WIDTH - (closeW + indexW + gap)) / 2F;
        drawTexturedButton(BUTTON_CLOSE, BUTTON_CLOSE_LIT, left, y, closeW, height,
                GuiLang.raw("gui.pvzce.almanac.close", "Close"), () -> requestClose());
        drawTexturedButton(BUTTON_INDEX, BUTTON_INDEX_LIT, left + closeW + gap, y, indexW, height,
                GuiLang.raw("gui.pvzce.almanac.index", "Index"), this::showIndex);
    }

    private void drawTexturedButton(Identifier texture, Identifier lit, float x, float y, float w,
                                    float h, String label, Runnable action) {
        boolean hovered = hovering(x, y, w, h);
        client.drawTexture(hovered ? lit : texture, sx(x), sy(y + h), sw(w), sw(h), 0.5F,
                1F, 1F, 1F, 1F);
        drawLineCentred(label, y + h - 11F, FOOTER_SCALE, BUTTON_WHITE);
        hits.add(new Hit(x, y, w, h, action));
    }

    /** A page-turning arrow, drawn as a triangle so it needs no extra art. */
    private void arrowButton(float x, float y, float w, float h, boolean forward, Runnable action) {
        boolean hovered = hovering(x, y, w, h);
        float alpha = hovered ? 0.95F : 0.62F;
        float midY = y + h / 2F;
        // Six stacked bars make a triangle; the painter has no triangle primitive and the depth
        // test is off, so a run of rects is both cheaper and exactly as crisp at this size.
        int steps = 12;
        for (int i = 0; i < steps; i++) {
            float t = i / (float) (steps - 1);
            float barW = 10F + t * (w - 10F);
            float barX = forward ? x : x + w - barW;
            client.drawSolid(sx(barX), sy(midY + (t - 0.5F) * h), sw(barW), sw(h / steps) + 1F,
                    0.5F, 0.18F, 0.10F, 0.03F, alpha);
        }
        hits.add(new Hit(x, y, w, h, action));
    }

    private boolean hovering(float x, float y, float w, float h) {
        var window = client.window();
        float nx = nativeX(client.guiMouseX(window.cursorX()));
        float ny = nativeY(client.guiMouseY(window.cursorY()));
        return nx >= x && nx <= x + w && ny >= y && ny <= y + h;
    }

    // ------------------------------------------------------------------------------------------
    // Text helpers, all in native units
    // ------------------------------------------------------------------------------------------

    /**
     * A line centred on the page, {@code nativeY} being the line's own baseline.
     *
     * <p>Named apart from {@link Screen}'s own centring helper because this one takes native units
     * and flips them; a same-signature overload would silently win over the base method and turn
     * every inherited caller upside down.
     */
    /** A line drawn from {@code nativeX} rightward. */
    private void drawTextLeft(String text, float nativeX, float nativeY, float fontScale,
                              float[] colour) {
        client.fonts().body().draw(text, sx(nativeX), sy(nativeY), fontScale,
                colour[0], colour[1], colour[2], colour[3]);
    }

    private void drawLineCentred(String text, float nativeY, float fontScale, float[] colour) {
        client.fonts().body().drawCentered(text, sx(NATIVE_WIDTH / 2F), sy(nativeY), fontScale,
                colour[0], colour[1], colour[2], colour[3]);
    }

    /** Wraps {@code text} into a native-space box, returning the y below the last line. */
    private float drawWrapped(String text, float x, float y, float width, float fontScale,
                              float[] ink, float alpha) {
        List<String> lines = client.fonts().body().wrapLines(text, sw(width), fontScale);
        float lineHeight = client.fonts().body().lineHeight(fontScale) / Math.max(0.01F, scale);
        float cursor = y;
        for (String line : lines) {
            client.fonts().body().draw(line, sx(x), sy(cursor), fontScale, ink[0], ink[1], ink[2], alpha);
            cursor -= lineHeight;
        }
        return cursor;
    }

    /** As {@link #drawWrapped}, centred on the box's horizontal middle. */
    private float drawWrappedCentred(String text, float x, float y, float width, float fontScale,
                                     float[] ink, float alpha) {
        List<String> lines = client.fonts().body().wrapLines(text, sw(width), fontScale);
        float lineHeight = client.fonts().body().lineHeight(fontScale) / Math.max(0.01F, scale);
        float cursor = y;
        for (String line : lines) {
            client.fonts().body().drawCentered(line, sx(x + width / 2F), sy(cursor), fontScale,
                    ink[0], ink[1], ink[2], alpha);
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
        // Topmost first: the footer and the arrows are added after the card, and a click on the
        // card under a button must not fall through to it.
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
        // A wheel over the book turns its pages: the only scrollable thing here is which entry is
        // open, and the original has no scrolling at all.
        if (page >= 0) {
            turn(amount > 0 ? 1 : -1);
        }
    }

    @Override
    public void keyPressed(int key) {
        if (page >= 0) {
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT || key == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN) {
                turn(1);
                return;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT || key == org.lwjgl.glfw.GLFW.GLFW_KEY_UP) {
                turn(-1);
                return;
            }
            if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE) {
                showIndex();
                return;
            }
        }
        super.keyPressed(key);
    }

    // ------------------------------------------------------------------------------------------
    // Read-only accessors, for the smoke driver and the tests
    // ------------------------------------------------------------------------------------------

    /** {@code -1} on the index, otherwise the page being read. */
    public int pageIndex() {
        return page;
    }

    /** The entry being read, or {@code null} on the index. */
    public Identifier currentEntry() {
        AlmanacEntries.Catalogue catalogue = current();
        return catalogue == null ? null : catalogue.at(entry);
    }

    /**
     * Opens a page without a click, so a screenshot run can reach any of them.
     *
     * <p>Safe to call before the screen has been initialized: the request is remembered and
     * applied by {@link #init()}, which otherwise runs on the first rendered frame and would
     * discard it.
     */
    public void show(AlmanacEntries.Page target) {
        if (catalogues.isEmpty()) {
            wanted = target;
            return;
        }
        openPage(target);
    }

    /**
     * Turns to an entry by index, wrapping; for the smoke driver's screenshot runs.
     *
     * <p>Applies after {@link #show} either way: when the screen has not been initialized yet, the
     * page request is pending and this index is applied to it by {@link #init()}.
     */
    public void showEntry(int index) {
        pendingEntry = index;
        AlmanacEntries.Catalogue catalogue = current();
        if (catalogue == null || catalogue.size() == 0) {
            return;
        }
        entry = Math.floorMod(index, catalogue.size());
        rebuildPreview();
    }

    /** How many entries the open page holds, or {@code 0} on the index. */
    public int entryCount() {
        AlmanacEntries.Catalogue catalogue = current();
        return catalogue == null ? 0 : catalogue.size();
    }
}
