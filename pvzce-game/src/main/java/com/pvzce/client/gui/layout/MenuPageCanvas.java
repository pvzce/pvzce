package com.pvzce.client.gui.layout;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.gui.components.NinePatch;
import com.pvzce.client.renderer.font.Fonts;

/**
 * The 800x600 canvas the two "menu pages" are composed on, and the pieces they compose with.
 *
 * <p>{@code ShopScreen} and {@code PackScreen} were the only two pages laid out directly in GUI
 * units, and at the default window that is a 427x240 strip: a three-row list whose 44-unit rows
 * left the wallet line sitting on top of the third row, and a two-column page whose detail text
 * was drawn straight onto the background image. Everything else in the game that has a real
 * composition - the almanac, the seed chooser, the level list - composes on a fixed canvas and
 * scales it to the window, so a page looks the same at every size and its panels are drawn, not
 * implied. This is that canvas, once, for both pages.
 *
 * <h2>Native space</h2>
 *
 * <p>The canvas is {@link #NATIVE_WIDTH}x{@link #NATIVE_HEIGHT} and its y axis grows <b>downward
 * from the top</b> - the arithmetic every measurement off a screenshot is done in. GUI space
 * grows upward from the bottom, so {@link Canvas#y} flips. The fit is a <em>contain</em> fit:
 * a 16:9 window is wider than the canvas, and the alternative (cover) would push the panels off
 * the top and bottom.
 *
 * <p>Widgets are <b>not</b> on this canvas: they stay in GUI units so their labels keep the
 * window's own text size, and a screen that wants a widget inside a native panel maps the panel's
 * rectangle with {@link Canvas#x}/{@link Canvas#y}/{@link Canvas#scaled} and puts it there. That
 * mix is deliberate - it is what keeps {@code AbstractSelectionList} (the only scrolling thing in
 * the game) usable inside a decorated panel.
 */
public final class MenuPageCanvas {
    /** The canvas every menu page is composed on; sizes below are measured in these units. */
    public static final float NATIVE_WIDTH = 800F;
    public static final float NATIVE_HEIGHT = 600F;

    /**
     * The floor on the canvas scale.
     *
     * <p>A fit, not a preference: the canvas <b>must</b> fit, so this is only high enough to keep
     * the arithmetic away from zero. The first version had a 0.55 floor "for legibility", and at
     * the default window (GUI 427x240) the canvas needs 0.4 - so the floor won and the header and
     * the footer were drawn 45 units off the top of the screen. A page whose title is invisible is
     * worse than a small title.
     */
    private static final float MIN_SCALE = 0.2F;
    private static final float MAX_SCALE = 2.6F;

    /** The page background: the same field the title screen stands on. */
    private static final Identifier BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/screen/title/title_background");

    /**
     * The wooden panel every block on these pages is framed with, and its nine-slice bounds.
     *
     * <p>{@code seed_chooser_background} is the game's own shallow box: a brown carved frame around
     * a dark, almost flat interior. The interior is the point - it is a surface text can sit on,
     * which is what the old pages were missing when they drew dark rectangles over a screenshot.
     * The bounds are read off the image: the frame is 9 pixels on the sides, and 32 at the top and
     * bottom including the corner ornaments.
     */
    private static final Identifier PANEL =
            Identifier.withDefaultNamespace("textures/gui/screen/seeds/seed_chooser_background");
    private static final float PANEL_TEXTURE_WIDTH = 465F;
    private static final float PANEL_TEXTURE_HEIGHT = 513F;
    private static final float PANEL_SIDE = 9F;
    private static final float PANEL_EDGE = 32F;

    /**
     * The darker band behind a page's title, at the top of the canvas.
     *
     * <p>The wood panel cannot be the whole page: its frame alone costs 64 of a 110-unit band, so
     * the title sits on a flat bar with a lighter top edge - the same "plate" the rest of the
     * game's menu text uses, drawn here rather than textured because no asset in the repository is
     * a shallow title bar. A thin gold line under it is what makes the two read as one frame.
     */
    private static final float HEADER_HEIGHT = 58F;
    private static final float GOLD_R = 0.85F;
    private static final float GOLD_G = 0.68F;
    private static final float GOLD_B = 0.3F;

    private MenuPageCanvas() {
    }

    // ------------------------------------------------------------------
    // Background
    // ------------------------------------------------------------------

    /**
     * Paints a page's field: the game's own background image, cover-fitted, under a flat dim.
     *
     * <p>Both pages used to ask for the blurred frame they were opened over, which at any window
     * size is a screenshot of the title screen at 62% opacity - legible panels on top of a
     * photograph, which is the thing this replaces. The image plus a dim is calmer, and it is the
     * same field the title screen itself is on, so arriving here does not change the world.
     */
    public static void renderPageBackground(PvzceClient client, Screen screen, float dim) {
        if (client.hasTexture(BACKGROUND)) {
            var tex = client.textures().getOrLoad(BACKGROUND);
            Screen.CoverFit fit = screen.coverFitFor(tex.width(), tex.height());
            client.drawTexture(BACKGROUND, fit.offsetX(), fit.offsetY(),
                    fit.drawWidth(), fit.drawHeight(), -2F, 1F, 1F, 1F, 1F);
        } else {
            client.drawSolid(0, 0, client.guiWidth(), client.guiHeight(), -2F, 0.09F, 0.07F, 0.05F, 1F);
        }
        client.drawSolid(0, 0, client.guiWidth(), client.guiHeight(), -1F, 0F, 0F, 0F, dim);
    }

    // ------------------------------------------------------------------
    // The canvas
    // ------------------------------------------------------------------

    /**
     * The mapping for the current window: one scale, one origin, and every conversion in and out
     * of native space.
     *
     * <p>A record rather than a handful of fields on each screen because two pages need the same
     * arithmetic, and the first version of this project's click mapping (a screen that repeated
     * the cover-fit formula instead of asking the base class for it) is exactly how a drawn panel
     * and its clickable rectangle drift apart.
     */
    public record Canvas(float scale, float originX, float originY) {
        /** Native x to GUI x. */
        public float x(float nativeX) {
            return originX + nativeX * scale;
        }

        /** Native y (downward from the top) to GUI y (upward from the bottom). */
        public float y(float nativeY) {
            return originY + (NATIVE_HEIGHT - nativeY) * scale;
        }

        /** A native length to a GUI length. */
        public float scaled(float nativeLength) {
            return nativeLength * scale;
        }

        /** Text of size {@code size} in native units, drawn at a native point. */
        public void text(PvzceClient client, Fonts.FontRole role, String text,
                         float nativeX, float nativeY, float size,
                         float r, float g, float b, float a) {
            role.draw(text, x(nativeX), y(nativeY), size * scale, r, g, b, a);
        }

        /** Text right-aligned on a native x, drawn at a native baseline. */
        public void textRight(PvzceClient client, Fonts.FontRole role, String text,
                              float nativeRightX, float nativeY, float size,
                              float r, float g, float b, float a) {
            role.drawRight(text, x(nativeRightX), y(nativeY), size * scale, r, g, b, a);
        }

        /** A solid native rectangle. */
        public void solid(PvzceClient client, float nativeX, float nativeY,
                          float nativeWidth, float nativeHeight, float r, float g, float b, float a) {
            client.drawSolid(x(nativeX), y(nativeY + nativeHeight),
                    scaled(nativeWidth), scaled(nativeHeight), 0F, r, g, b, a);
        }

        /**
         * The wooden panel: a rectangle in native space, framed with the game's own box.
         *
         * <p>The nine-slice is drawn in GUI space at the fitted scale, because the slice bounds are
         * pixels of the source image and must stay square - scaling the rectangle and then slicing
         * it would stretch the frame's corners along with the panel.
         */
        public void panel(PvzceClient client, float nativeX, float nativeY,
                          float nativeWidth, float nativeHeight) {
            NinePatch.drawNineSlice(client, PANEL,
                    x(nativeX), y(nativeY + nativeHeight),
                    scaled(nativeWidth), scaled(nativeHeight), 0F,
                    PANEL_TEXTURE_WIDTH, PANEL_TEXTURE_HEIGHT,
                    PANEL_SIDE, PANEL_SIDE, PANEL_EDGE, PANEL_EDGE,
                    1F, 1F, 1F, 1F);
        }

        /**
         * Where a panel's usable interior starts and how big it is, in GUI units.
         *
         * <p>A list that has to live inside {@link #panel} needs these four numbers rather than the
         * panel's own rectangle, and deriving them here is what keeps the frame's width in one
         * place: the panel's border is a fixed number of <em>source pixels</em>, so it costs the
         * same fraction of the canvas at every window size.
         */
        public float interiorX(float nativeX) {
            return x(nativeX) + scaled(PANEL_SIDE);
        }

        /** GUI width of {@code nativeWidth} minus a panel's two side borders. */
        public float interiorWidth(float nativeWidth) {
            return Math.max(1F, scaled(nativeWidth - PANEL_SIDE * 2F));
        }

        /** GUI y of the bottom of a native rectangle's panel interior. */
        public float interiorBottom(float nativeY, float nativeHeight) {
            return y(nativeY + nativeHeight) + scaled(PANEL_EDGE);
        }

        /** GUI height of {@code nativeHeight} minus a panel's two horizontal borders. */
        public float interiorHeight(float nativeHeight) {
            return Math.max(1F, scaled(nativeHeight - PANEL_EDGE * 2F));
        }

        /**
         * A widget placed by its native centre and bottom edge.
         *
         * <p>Widgets stay in GUI units - their labels have to keep the window's own text size -
         * but these pages are composed in native ones, so the conversion has to happen somewhere.
         * Here rather than in each page: the same "native centre and width become a GUI rectangle"
         * written twice is how a drawn frame and its clickable area start to disagree. The centre
         * is the anchor because that is how the pages are composed ("centred on the canvas, 56
         * units up from the bottom"), not because a corner would be wrong.
         */
        public Button widgetAt(PvzceClient client, float nativeCenterX, float nativeY,
                               float nativeWidth, float nativeHeight,
                               String label, Runnable onPress) {
            int width = Math.round(scaled(nativeWidth));
            int height = Math.round(scaled(nativeHeight));
            int widgetX = Math.round(x(nativeCenterX) - width / 2F);
            int widgetY = Math.round(y(nativeY));
            return new Button(widgetX, widgetY, width, height, label, onPress);
        }

        /** The page's title band: the flat bar with a gold rule under it. */
        public void header(PvzceClient client, float nativeWidth) {
            solid(client, 0F, 0F, nativeWidth, HEADER_HEIGHT, 0.10F, 0.11F, 0.14F, 0.90F);
            solid(client, 0F, 0F, nativeWidth, 2F, 0.30F, 0.32F, 0.36F, 0.55F);
            solid(client, 0F, HEADER_HEIGHT, nativeWidth, 1.5F, GOLD_R, GOLD_G, GOLD_B, 0.75F);
        }
    }

    /**
     * The canvas for the current window.
     *
     * <p>Recomputed every frame rather than in {@code init()}: a frame can be the first one after a
     * resize, and {@code init()} has already run by then.
     */
    public static Canvas fit(PvzceClient client) {
        float scale = Math.min(client.guiWidth() / NATIVE_WIDTH, client.guiHeight() / NATIVE_HEIGHT);
        scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        float originX = (client.guiWidth() - NATIVE_WIDTH * scale) / 2F;
        float originY = (client.guiHeight() - NATIVE_HEIGHT * scale) / 2F;
        return new Canvas(scale, originX, originY);
    }
}
