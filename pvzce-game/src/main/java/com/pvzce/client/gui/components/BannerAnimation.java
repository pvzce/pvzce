package com.pvzce.client.gui.components;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.common.util.MathUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * The full-screen word banners: <em>准备… 安放… 植物！</em> when a level begins, and
 * <em>一大波僵尸！</em> when the last wave arrives.
 *
 * <p><strong>Text, not art.</strong> These were the original's own PNGs
 * ({@code StartReady/StartSet/StartPlant.png}, {@code FinalWave.png}) - English lettering baked
 * into a picture, which no locale could change and no pack could restyle, while every other line
 * the game says is text. The words now come from {@code assets/pvzce/lang} through
 * {@link GuiLang} and are drawn in the display face, red on a black copy like the art they
 * replace. What is kept from the art is everything that is not a language: the <em>timing and
 * motion</em>, still taken frame by frame from the original reanims -
 * {@code refer/anim/StartReadySetPlant.reanim} and {@code refer/anim/FinalWave.reanim}, both at
 * 12 fps.
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
    /** Lang keys; the fallbacks are the same words the shipped lang files carry. */
    private static final String READY = "pvzce.announce.ready";
    private static final String SET = "pvzce.announce.set";
    private static final String PLANT = "pvzce.announce.plant";
    private static final String FINAL_WAVE = "pvzce.announce.final_wave";
    private static final String READY_FALLBACK = "准备…";
    private static final String SET_FALLBACK = "安放…";
    private static final String PLANT_FALLBACK = "植物！";
    private static final String FINAL_WAVE_FALLBACK = "最后一波攻势";

    /**
     * The width the poses' pixel offsets were authored against - the original art's cell. A
     * constant rather than a picture, because those offsets are the only thing left that is still
     * expressed in source pixels.
     */
    private static final float SOURCE_WIDTH = 300F;
    /** The original's banner framerate; every pose below is one frame at this rate. */
    private static final float FPS = 12F;

    /*
     * The beats are the sound's, not the reanim's. The poses below were lifted from the original's
     * animation, but the original's *animation* and this project's *audio* are two different files,
     * and running the poses at the reanim's own spacing (half a second a word) finished the banner
     * in 1.8s while `readysetplant.ogg` runs 5.26s: two thuds at 0.00s and 0.60s, then the words
     * over a sustained passage from 1.20s to 3.30s. The words now land on the thuds and 植物！holds
     * over the passage, which is what "in time with the sound" means here. The same measurement
     * drove the final wave: `awooga.ogg` is 4.15s and only reaches its peak at 2.2s, so a banner
     * that flew in and left in 1.9s was over before the sound it announces got loud.
     */
    /** When each word of the entry banner arrives, in seconds: on the two thuds and the passage. */
    private static final float READY_AT = 0.00F;
    private static final float SET_AT = 0.60F;
    private static final float PLANT_AT = 1.20F;
    /** How long 植物！stays after it lands - the sound's passage runs to 3.30s. */
    private static final float PLANT_HOLD = 2.05F;
    /**
     * How long a word of the entry banner stays once it has landed, before the next replaces it.
     *
     * <p>Measured from the sound like the arrivals: 准备… covers the thud at 0.00s until 安放…
     * lands on the one at 0.60s, so a beat is 0.60s and the arrival takes five of its frames.
     */
    private static final float READY_HOLD = SET_AT - READY_AT;
    private static final float SET_HOLD = PLANT_AT - SET_AT;
    /** The final wave: the fly-in is stretched to reach the peak at 2.20s, then it holds. */
    private static final float FINAL_ARRIVAL = 1.10F;
    private static final float FINAL_HOLD = 2.40F;
    /**
     * How tall the words are, and how wide they may be, as fractions of the drawn width.
     *
     * <p>Measured off the art they replace: its lettering is 102px tall in a 300px-wide cell and
     * 256 of those 300 pixels wide. Height leads and width is the guard, because Chinese is more
     * compact than the English it replaces - 一大波僵尸！ at the English lettering's height would be
     * wider than the window it is shouted across.
     */
    private static final float TEXT_HEIGHT_OF_DRAWN_WIDTH = 0.36F;
    private static final float TEXT_WIDTH_OF_DRAWN_WIDTH = 0.92F;
    /** The dark copy behind the letters, as a fraction of their height: the art's own outline. */
    private static final float OUTLINE_OF_TEXT_HEIGHT = 0.07F;
    /** The art's lettering: a strong red, on a near-black outline. */
    private static final float TEXT_R = 0.65F;
    private static final float TEXT_G = 0.01F;
    private static final float TEXT_B = 0.01F;
    private static final float OUTLINE_R = 0.05F;
    private static final float OUTLINE_G = 0.01F;
    private static final float OUTLINE_B = 0.01F;

    /**
     * One word of a banner: what it says, the frame it appears on, and its poses.
     *
     * @param frames {@code {dx, dy, scale}} per frame, in source pixels relative to the
     *               word's first frame
     */
    private record Word(String key, String fallback, int firstFrame, int frameCount,
                        float[][] frames) {
    }

    /**
     * One word as it looks at one instant, positioned relative to the banner's centre.
     *
     * <p>{@code text} is already resolved through {@link GuiLang}, so a caller (and a test) sees
     * the words that will be drawn; the offsets are fractions of the drawn width.
     */
    public record Placed(String text, float offsetX, float offsetY, float scale, float alpha) {
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
     * The opening beats: 准备… 安放… 植物！, one word at a time.
     *
     * <p>Each word flies in over five frames of the reanim (a drift up and to the left while
     * growing 10%) and then holds until the next one lands, which is what the original does - the
     * words are on screen for the whole beat rather than appearing and vanishing inside it.
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
        // word land rather than grow, which is what makes it read as the last beat - and then it
        // holds, on screen, for the rest of the sound.
        float[][] plant = constant(secondsToFrames(PLANT_HOLD), 1.300F);
        return new BannerAnimation(List.of(
                new Word(READY, READY_FALLBACK, secondsToFrames(READY_AT),
                        secondsToFrames(READY_HOLD), ready),
                new Word(SET, SET_FALLBACK, secondsToFrames(SET_AT),
                        secondsToFrames(SET_HOLD), set),
                new Word(PLANT, PLANT_FALLBACK, secondsToFrames(PLANT_AT),
                        plant.length, plant)));
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
        int arrivalFrames = secondsToFrames(FINAL_ARRIVAL);
        int holdFrames = secondsToFrames(FINAL_HOLD);
        float[][] arrival = stretch(poses, arrivalFrames);
        float[][] frames = new float[arrivalFrames + holdFrames + 1][];
        System.arraycopy(arrival, 0, frames, 0, arrivalFrames);
        for (int i = 0; i < holdFrames; i++) {
            frames[arrivalFrames + i] = new float[]{0F, 0F, 1.000F, 1.00F};
        }
        // The reanim drops the alpha to zero on the frame after the hold instead of cutting.
        frames[frames.length - 1] = new float[]{0F, 0F, 1.000F, 0.00F};
        return new BannerAnimation(List.of(
                new Word(FINAL_WAVE, FINAL_WAVE_FALLBACK, 0, frames.length, frames)));
    }

    /** How many frames of this banner's clock a duration in seconds is. */
    private static int secondsToFrames(float seconds) {
        return Math.round(seconds * FPS);
    }

    /** A pose list of {@code frames} entries that all look the same: a hold, or a landing. */
    private static float[][] constant(int frames, float scale) {
        float[][] poses = new float[frames][];
        for (int i = 0; i < frames; i++) {
            poses[i] = new float[]{0F, 0F, scale};
        }
        return poses;
    }

    /**
     * The same motion spread over more frames: the fly-in poses stretched to fill a longer arrival.
     *
     * <p>Poses are one per frame, so stretching means repeating them - at twelve frames a second the
     * result reads as the same movement, slower, which is the point: the final wave's art flew in
     * over 0.75s, well before the sound it announces becomes loud.
     */
    private static float[][] stretch(float[][] poses, int frames) {
        if (frames <= poses.length) {
            return poses;
        }
        float[][] out = new float[frames][];
        for (int i = 0; i < frames; i++) {
            out[i] = poses[Math.min(poses.length - 1, i * poses.length / frames)];
        }
        return out;
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
            placed.add(new Placed(GuiLang.raw(word.key(), word.fallback()),
                    dx / SOURCE_WIDTH, dy / SOURCE_WIDTH, scale, alpha));
        }
        return placed;
    }

    /**
     * The scale one word is drawn at: as tall as it is allowed to be, pulled back if that would
     * make it wider than it is allowed to be.
     *
     * <p>Pure - {@code unitHeight} and {@code textWidth} are what the role reports at scale 1 - so
     * "a banner word fits the space it was given" is checkable without a window. Height leads
     * because the art's words were all the same height and different widths, and Chinese is
     * compact enough that the height is what normally decides; the width is the guard that keeps a
     * long word - 一大波僵尸！is six characters - on the screen it is shouted across.
     */
    static float wordScale(float drawnWidth, float unitHeight, float textWidth) {
        float byHeight = drawnWidth * TEXT_HEIGHT_OF_DRAWN_WIDTH / Math.max(1F, unitHeight);
        float byWidth = drawnWidth * TEXT_WIDTH_OF_DRAWN_WIDTH / Math.max(1F, textWidth);
        return Math.min(byHeight, byWidth);
    }

    /**
     * Draws the banner with its centre at {@code (centerX, centerY)}.
     *
     * <p>Five passes per word: the word in black nudged each way, then the letters on top - the
     * black edge the art had, drawn rather than baked. The shader's outline cannot stand in for it
     * here: its sampling distance is capped at two device pixels, which around lettering this size
     * is a hairline.
     *
     * @param drawnWidth the width the words are laid out against; offsets and size follow it, so
     *                   the banner keeps its proportions in any window
     */
    public void render(PvzceClient client, float seconds, float centerX, float centerY, float drawnWidth) {
        com.pvzce.client.renderer.font.Fonts.FontRole face = client.fonts().button();
        for (Placed word : sample(seconds)) {
            if (word.alpha() <= 0.001F || word.text().isEmpty()) {
                continue;
            }
            float unit = Math.max(1F, face.lineHeight(1F));
            float textWidth = Math.max(1F, face.width(word.text(), 1F));
            float scale = wordScale(drawnWidth, unit, textWidth) * word.scale();
            float width = face.width(word.text(), scale);
            float ascent = face.ascent(scale);
            float descent = face.lineHeight(scale) - ascent;
            // The words are centred on their own box, not on their baseline: `centerY` is where the
            // middle of the lettering goes, as it was for the art.
            float baseline = centerY + word.offsetY() * drawnWidth - (ascent - descent) / 2F;
            float x = centerX + word.offsetX() * drawnWidth - width / 2F;
            // The outline is the same word drawn in black around the letters, four ways - the art
            // had a black edge on every side, not a drop shadow. Four rather than eight: at this
            // size the diagonals are covered by the horizontal and vertical passes anyway, and a
            // banner is nine draws per word instead of seventeen.
            float outline = face.lineHeight(scale) * OUTLINE_OF_TEXT_HEIGHT;
            for (int pass = 0; pass < 4; pass++) {
                float dx = pass == 0 ? outline : pass == 1 ? -outline : 0F;
                float dy = pass == 2 ? outline : pass == 3 ? -outline : 0F;
                face.draw(word.text(), x + dx, baseline + dy, scale,
                        OUTLINE_R, OUTLINE_G, OUTLINE_B, word.alpha());
            }
            face.draw(word.text(), x, baseline, scale, TEXT_R, TEXT_G, TEXT_B, word.alpha());
        }
    }
}
