package com.pvzce.client.gui.components;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.PvzceClient;
import com.pvzce.common.util.MathUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * The original's full-screen word banners: <em>Ready... Set... Plant!</em> when a level
 * begins, and <em>FINAL WAVE</em> when the last wave arrives.
 *
 * <p>Both are drawn from the game's own art ({@code StartReady/StartSet/StartPlant.png},
 * {@code FinalWave.png}) and both are timed from the original reanims, which is where the
 * numbers below come from:
 * {@code refer/anim/StartReadySetPlant.reanim} and {@code refer/anim/FinalWave.reanim},
 * both at 12 fps. They used to be one line of text drawn in the project's UI font, which is
 * a different typeface with a different feel - the banner is the game's voice, not a label.
 *
 * <p><strong>Frame data, not a curve.</strong> Each word is a list of per-frame poses
 * ({@code dx, dy, scale}) exactly as the reanim writes them, sampled with linear
 * interpolation between frames - the same thing the animation player does for entity art.
 * The only liberty taken is the <em>anchor</em>: the reanim places these words against an
 * 800x600 stage origin, and this draws each word centred on the point the caller gives, so
 * the banner sits in the middle of whatever window the game is running in. Offsets are
 * expressed in fractions of the word's own width, so the motion scales with the art.
 *
 * <p>One banner is a sequence of words (Ready, then Set, then Plant) or a single one (FINAL
 * WAVE). {@link #sample(float)} is pure and returns what to draw, which is what the tests
 * pin; {@link #render} turns that into pixels.
 */
public final class BannerAnimation {
    /** {@code assets/pvzce/textures/gui/hud/announce/}. */
    private static final Identifier READY = id("ready");
    private static final Identifier SET = id("set");
    private static final Identifier PLANT = id("plant");
    private static final Identifier FINAL_WAVE = id("final_wave");

    /** The original's banner framerate; every pose below is one frame at this rate. */
    private static final float FPS = 12F;
    /** Source width of each word's art, the unit the offsets are fractions of. */
    private static final float READY_SET_PLANT_WIDTH = 300F;
    private static final float FINAL_WAVE_WIDTH = 341F;

    /**
     * One word of a banner: its art, the frame it appears on, and its poses.
     *
     * @param frames {@code {dx, dy, scale}} per frame, in source pixels relative to the
     *               word's first frame
     */
    private record Word(Identifier texture, int firstFrame, int frameCount, float sourceWidth,
                        float[][] frames) {
    }

    /** One word as it looks at one instant, positioned relative to the banner's centre. */
    public record Placed(Identifier texture, float offsetX, float offsetY, float scale, float alpha) {
    }

    private final List<Word> words;
    private final float durationSeconds;

    private BannerAnimation(List<Word> words) {
        this.words = List.copyOf(words);
        int last = 0;
        for (Word word : words) {
            last = Math.max(last, word.firstFrame() + word.frameCount());
        }
        this.durationSeconds = last / FPS;
    }

    public float duration() {
        return durationSeconds;
    }

    /**
     * "Ready... Set... Plant!", one word at a time, half a second each.
     *
     * <p>The reanim's own timing: each word flies in over five frames (a drift up and to the
     * left while growing 10%) and then holds for one more before the next replaces it.
     */
    public static BannerAnimation readySetPlant() {
        float[][] ready = {
                {0F, 0F, 1.000F},
                {-2.9F, -1.7F, 1.020F},
                {-5.9F, -3.3F, 1.040F},
                {-8.8F, -5.0F, 1.060F},
                {-11.8F, -6.7F, 1.080F},
                {-14.7F, -8.4F, 1.100F},
        };
        float[][] set = {
                {0F, 0F, 1.000F},
                {-3.0F, -1.6F, 1.020F},
                {-6.0F, -3.3F, 1.040F},
                {-9.0F, -5.0F, 1.060F},
                {-12.0F, -6.7F, 1.080F},
                {-15.1F, -8.3F, 1.100F},
        };
        // "Plant!" is already at its final size the moment it appears: the original lets the
        // word land rather than grow, which is what makes it read as the last beat.
        float[][] plant = new float[10][];
        for (int i = 0; i < plant.length; i++) {
            plant[i] = new float[]{0F, 0F, 1.300F};
        }
        return new BannerAnimation(List.of(
                new Word(READY, 0, 6, READY_SET_PLANT_WIDTH, ready),
                new Word(SET, 6, 6, READY_SET_PLANT_WIDTH, set),
                new Word(PLANT, 12, 10, READY_SET_PLANT_WIDTH, plant)));
    }

    /**
     * "FINAL WAVE": flies in from the lower left, growing down from 3.9x and fading in,
     * holds for about a second, then fades out on the last frame.
     */
    public static BannerAnimation finalWave() {
        // dx/dy are relative to the reanim's resting pose (x=220.1, y=260.1), which is the
        // pose that ends up centred on screen; the y axis of the source points down, so the
        // banner rises as it arrives.
        float[][] poses = {
                {-522.8F, -116.1F, 3.905F, 0.00F},
                {-490.5F, -108.9F, 3.727F, 0.13F},
                {-455.3F, -101.1F, 3.531F, 0.25F},
                {-416.2F, -92.4F, 3.314F, 0.38F},
                {-371.4F, -82.5F, 3.065F, 0.50F},
                {-318.3F, -70.7F, 2.770F, 0.63F},
                {-251.5F, -55.8F, 2.398F, 0.75F},
                {-159.3F, -35.4F, 1.885F, 0.88F},
                {0F, 0F, 1.000F, 1.00F},
        };
        int holdFrames = 13;
        float[][] frames = new float[poses.length + holdFrames + 1][];
        System.arraycopy(poses, 0, frames, 0, poses.length);
        for (int i = 0; i < holdFrames; i++) {
            frames[poses.length + i] = new float[]{0F, 0F, 1.000F, 1.00F};
        }
        // The reanim drops the alpha to zero on the frame after the hold instead of cutting.
        frames[frames.length - 1] = new float[]{0F, 0F, 1.000F, 0.00F};
        return new BannerAnimation(List.of(
                new Word(FINAL_WAVE, 0, frames.length, FINAL_WAVE_WIDTH, frames)));
    }

    /**
     * What the banner looks like {@code seconds} after it started; empty once it is over.
     *
     * <p>Pure: the caller supplies the clock, so the fade and the fly-in can be tested
     * without a window.
     */
    public List<Placed> sample(float seconds) {
        if (seconds < 0F || seconds >= durationSeconds) {
            return List.of();
        }
        float frame = seconds * FPS;
        List<Placed> placed = new ArrayList<>();
        for (Word word : words) {
            if (frame < word.firstFrame() || frame >= word.firstFrame() + word.frameCount()) {
                continue;
            }
            float local = frame - word.firstFrame();
            int index = Math.min(word.frames().length - 1, (int) local);
            float[] from = word.frames()[index];
            float[] to = word.frames()[Math.min(word.frames().length - 1, index + 1)];
            float t = Math.min(1F, local - index);
            float dx = MathUtil.lerp(from[0], to[0], t);
            float dy = MathUtil.lerp(from[1], to[1], t);
            float scale = MathUtil.lerp(from[2], to[2], t);
            // A fourth component is the alpha; banners without one are fully opaque.
            float alpha = from.length > 3 ? MathUtil.lerp(from[3], to[3], t) : 1F;
            placed.add(new Placed(word.texture(),
                    dx / word.sourceWidth(), dy / word.sourceWidth(), scale, alpha));
        }
        return placed;
    }

    /**
     * Draws the banner with its centre at {@code (centerX, centerY)}.
     *
     * @param drawnWidth how wide the words are drawn; offsets and scale follow it, so the
     *                   banner keeps its proportions in any window
     */
    public void render(PvzceClient client, float seconds, float centerX, float centerY, float drawnWidth) {
        for (Placed word : sample(seconds)) {
            if (word.alpha() <= 0.001F) {
                continue;
            }
            float width = drawnWidth * word.scale();
            float height = width * artAspect(client, word.texture());
            client.drawTexture(word.texture(),
                    centerX + word.offsetX() * drawnWidth - width / 2F,
                    centerY + word.offsetY() * drawnWidth - height / 2F,
                    width, height, 0.6F, 1F, 1F, 1F, word.alpha());
        }
    }

    /** A word's height for a given width, from its own art. Square when it cannot be read. */
    private static float artAspect(PvzceClient client, Identifier texture) {
        try {
            var loaded = client.textures().getOrLoad(texture);
            return loaded.height() / (float) Math.max(1, loaded.width());
        } catch (RuntimeException ignored) {
            return 1F;
        }
    }

    private static Identifier id(String path) {
        return Identifier.withDefaultNamespace("textures/gui/hud/announce/" + path);
    }
}
