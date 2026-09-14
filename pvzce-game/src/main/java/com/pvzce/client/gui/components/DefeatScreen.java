package com.pvzce.client.gui.components;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;



/**
 * The original's defeat screen: the lawn dims to black and "THE ZOMBIES ATE YOUR BRAINS"
 * grows out of the middle of it.
 *
 * <p>Frame data, not a curve, and taken from {@code refer/anim/ZombiesWon.reanim} at 24
 * fps - the same discipline as {@link BannerAnimation}: the reanim holds a 16x16 pixel
 * scaled by 50x37.5 to cover an 800x600 stage while its alpha climbs to 0.5, and the words
 * growing from 1.7% to full size over twelve frames. The one liberty is the anchor: the
 * words are centred on the window rather than on the reanim's own 800x600 stage, so the
 * screen works at any window shape.
 *
 * <p>It replaced a line of text ("失败！") drawn in the project's UI font, which said the
 * same thing in a typeface the game never uses.
 */
public final class DefeatScreen {
    /** {@code assets/pvzce/textures/gui/hud/announce/}; the original's own art. */
    private static final Identifier WORDS = Identifier.withDefaultNamespace(
            "textures/gui/hud/announce/zombies_won");

    private static final float FPS = 24F;
    /** The words' authored size, which the reanim's scale is relative to. */
    private static final float WORDS_NATIVE_WIDTH = 564F;
    private static final float WORDS_NATIVE_HEIGHT = 468F;
    /**
     * How wide the words end up, as a fraction of the window's smaller dimension.
     *
     * <p>The reanim ends at the art's own size against an 800x600 stage - 70% of the width
     * - which is what this keeps, so the words fill the screen the way they did there
     * instead of scaling with a widescreen window's width.
     */
    private static final float WORDS_WIDTH_RATIO = 0.70F;
    /** The black cover the reanim fades in; its table ends at 0.5 and stays there. */
    private static final float COVER_MAX_ALPHA = 0.5F;
    /** Scale and cover alpha per frame, exactly as the reanim writes them. */
    private static final float[] WORD_SCALE = {
            0.017F, 0.106F, 0.196F, 0.285F, 0.374F, 0.464F, 0.553F, 0.642F, 0.732F, 0.821F,
            0.911F, 1.000F,
    };
    private static final float[] COVER_ALPHA = {
            0.00F, 0.05F, 0.09F, 0.14F, 0.18F, 0.23F, 0.27F, 0.32F, 0.36F, 0.41F, 0.45F,
            COVER_MAX_ALPHA,
    };

    /** How long the whole beat takes: twelve frames at the reanim's rate. */
    public static final float DURATION_SECONDS = WORD_SCALE.length / FPS;

    private DefeatScreen() {
    }

    /** The cover's opacity {@code seconds} in, clamped to its final value. */
    public static float coverAlpha(float seconds) {
        return raw(COVER_ALPHA, seconds);
    }

    /** The words' scale {@code seconds} in, clamped to their final value. */
    public static float wordScale(float seconds) {
        return raw(WORD_SCALE, seconds);
    }

    /** Samples a per-frame table with linear interpolation, holding the last value. */
    private static float raw(float[] table, float seconds) {
        if (seconds <= 0F) {
            return table[0];
        }
        float frame = seconds * FPS;
        int index = (int) frame;
        if (index >= table.length - 1) {
            return table[table.length - 1];
        }
        return com.pvzce.common.util.MathUtil.lerp(table[index], table[index + 1],
                frame - index);
    }

    /**
     * Draws the screen over whatever is behind it.
     *
     * @param seconds how long the screen has been up; the movement is over after
     *                {@link #DURATION_SECONDS} and the words simply stay
     */
    public static void render(PvzceClient client, float seconds) {
        float guiW = client.guiWidth();
        float guiH = client.guiHeight();
        client.drawSolid(0F, 0F, guiW, guiH, 0.5F, 0F, 0F, 0F, coverAlpha(seconds));

        float full = Math.min(guiW, guiH * (WORDS_NATIVE_WIDTH / WORDS_NATIVE_HEIGHT))
                * WORDS_WIDTH_RATIO;
        float width = full * wordScale(seconds);
        float height = width * WORDS_NATIVE_HEIGHT / WORDS_NATIVE_WIDTH;
        client.drawTexture(WORDS, (guiW - width) / 2F, (guiH - height) / 2F, width, height,
                0.6F, 1F, 1F, 1F, 1F);
    }

    /** True once the beat has played out and the words are simply being held. */
    public static boolean settled(float seconds) {
        return seconds >= DURATION_SECONDS;
    }
}
