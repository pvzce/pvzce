package com.pvzce.client.gui.layout;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.Screen;
import com.pvzce.client.gui.components.Button;
import com.pvzce.client.renderer.font.Fonts;

/** Shared parchment, inset boards and wooden actions for content menus.
 * The 800x600 canvas uses top-down native coordinates; widgets receive mapped GUI bounds.
 * The title screen also uses Canvas for its original 800x600 scene.
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

    /** Quiet parchment shared with the level menu. */
    private static final Identifier BACKGROUND =
            Identifier.withDefaultNamespace("textures/gui/screen/level/challenge_background");

    /** Content padding inside the thin board frame, in canvas units. */
    private static final float PANEL_SIDE = 14F;
    private static final float PANEL_EDGE = 20F;

    /** Space reserved for the page title and wallet or hint. */
    private static final float HEADER_HEIGHT = 58F;

    private MenuPageCanvas() {
    }

    // ------------------------------------------------------------------
    // Background
    // ------------------------------------------------------------------

    /** Cover-fit the level menu parchment; it leaves the content as the focal point. */
    public static void renderPageBackground(PvzceClient client, Screen screen) {
        if (client.hasTexture(BACKGROUND)) {
            var tex = client.textures().getOrLoad(BACKGROUND);
            Screen.CoverFit fit = screen.coverFitFor(tex.width(), tex.height());
            client.drawTexture(BACKGROUND, fit.offsetX(), fit.offsetY(),
                    fit.drawWidth(), fit.drawHeight(), -2F, 1F, 1F, 1F, 1F);
        } else {
            client.drawSolid(0, 0, client.guiWidth(), client.guiHeight(), -2F, 0.09F, 0.07F, 0.05F, 1F);
        }
    }

    /** A warm inset board; all sizes are GUI units, including the unobtrusive frame. */
    public static void surface(PvzceClient client, float x, float y, float width, float height, boolean highlighted) {
        client.drawSolid(x + 1, y - 2, width, height, 0, .12F, .07F, .025F, .25F);
        client.drawSolid(x, y, width, height, 0, highlighted ? .84F : .55F, highlighted ? .66F : .37F, .19F, 1);
        client.drawSolid(x + 2, y + 2, width - 4, height - 4, 0,
                highlighted ? .34F : .24F, highlighted ? .23F : .155F, .075F, 1);
    }

    /** A paper label for content without cover art, rather than a missing-texture square. */
    public static void documentIcon(PvzceClient client, float x, float y, float side, String mark) {
        float inset = side * .12F;
        client.drawSolid(x + inset, y, side - inset * 2, side, 0, .48F, .29F, .12F, 1);
        client.drawSolid(x + inset + 2, y + 2, side - inset * 2 - 4, side - 4, 0, .91F, .77F, .49F, 1);
        float scale = Math.min(side / 38F, 1.5F);
        client.fonts().button().drawCentered(mark, x + side / 2, y + side * .43F, scale, .29F, .17F, .065F, 1);
        client.drawSolid(x + side * .28F, y + side * .21F, side * .44F, Math.max(1, side * .025F), 0, .65F, .46F, .23F, 1);
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

        /** An inset board sharing the level menu palette. */
        public void panel(PvzceClient client, float nativeX, float nativeY,
                          float nativeWidth, float nativeHeight) {
            surface(client, x(nativeX), y(nativeY + nativeHeight), scaled(nativeWidth), scaled(nativeHeight), false);
        }

        /**
         * Where a panel's usable interior starts and how big it is, in GUI units.
         *
         * <p>A list that has to live inside {@link #panel} needs these four numbers rather than the
         * panel's own rectangle, and deriving them here is what keeps the frame's width in one
         * place: the content padding is measured on the canvas and scales with the page.
         */
        public float interiorX(float nativeX) {
            return x(nativeX) + scaled(PANEL_SIDE);
        }

        /** GUI width of {@code nativeWidth} minus a panel's two side borders. */
        public float interiorWidth(float nativeWidth) {
            return Math.max(1F, scaled(nativeWidth - PANEL_SIDE * 2F));
        }

        /** GUI x of a panel's right interior edge, for a control laid against it. */
        public float interiorRight(float nativeX, float nativeWidth) {
            return x(nativeX + nativeWidth) - scaled(PANEL_SIDE);
        }

        /** GUI y of the bottom of a native rectangle's panel interior. */
        public float interiorBottom(float nativeY, float nativeHeight) {
            return y(nativeY + nativeHeight) + scaled(PANEL_EDGE);
        }

        /** GUI y of the top of a native rectangle's panel interior. */
        public float interiorTop(float nativeY) {
            return y(nativeY) - scaled(PANEL_EDGE);
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
            return new Button(widgetX, widgetY, width, height, label, onPress).style(Button.Style.SEED_CHOOSER);
        }

        /** A quiet rule separating the title from the content. */
        public void header(PvzceClient client, float nativeWidth) {
            solid(client, 24F, HEADER_HEIGHT, nativeWidth - 48F, 1.5F, .49F, .29F, .12F, .45F);
        }

        /**
         * A widget pinned inside one of {@link #panel}'s corners, by an inset in native units.
         *
         * <p>"Bottom-right of this panel, 12 in from each edge" is how a control that belongs to a
         * panel is actually placed, and it is the only placement that cannot disagree with the
         * frame: the inset is counted from the same interior edges {@link #interiorX} and
         * {@link #interiorBottom} report. Building it out of those two keeps the content padding
         * in one place, and saves a caller from dividing
         * a GUI width back by {@link #scale()} to feed {@link #widgetAt}.
         */
        public Button widgetAtInterior(PvzceClient client, float nativeX, float nativeY,
                                       float nativeWidth, float nativeHeight,
                                       float insetRight, float insetBottom,
                                       float widgetWidth, float widgetHeight,
                                       String label, Runnable onPress) {
            int width = Math.round(scaled(widgetWidth));
            int height = Math.round(scaled(widgetHeight));
            int widgetX = Math.round(interiorRight(nativeX, nativeWidth) - scaled(insetRight) - width);
            int widgetY = Math.round(interiorBottom(nativeY, nativeHeight) + scaled(insetBottom));
            return new Button(widgetX, widgetY, width, height, label, onPress).style(Button.Style.SEED_CHOOSER);
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
