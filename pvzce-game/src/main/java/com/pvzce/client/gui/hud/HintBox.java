package com.pvzce.client.gui.hud;

import com.pvzce.api.content.LevelHint;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.gui.GuiLang;
import com.pvzce.common.util.MathUtil;

/**
 * The original's grey box at the bottom of the board, where the game talks to the player.
 *
 * <p>It says two kinds of thing, and they do not have the same standing:
 *
 * <ul>
 *   <li><b>{@link Kind#TUTORIAL}</b> - a line the level wrote ({@code hints} in its JSON):
 *       "click a sun to collect it". It stays for its own duration, or until the level
 *       ends when it asks to persist.</li>
 *   <li><b>{@link Kind#REFUSAL}</b> - the game answering a click it would not honour:
 *       "still recharging" or "not enough sun". Built in, because the sentence is about
 *       the rules rather than about this level.</li>
 * </ul>
 *
 * <p><b>One line at a time, and a refusal outranks a tutorial.</b> A refusal is the answer
 * to something the player just did; letting a tutorial line land on top of it would swallow
 * the answer at the exact moment it was asked for. The other direction is fine - a refusal
 * replaces a tutorial line, and the tutorial is gone, because by then the player has
 * already moved on.
 *
 * <p>Drawn in GUI space rather than world space: like the wave banner, this is the game
 * addressing the player, so it must not scale with the board or slide when the camera
 * pans toward the house on a loss.
 *
 * <p>Everything here is on the wall clock. The box is presentation and nothing else reads
 * it, so a paused or frozen level still fades its text out normally.
 */
public final class HintBox {
    /** Bottom margin of the box, in GUI pixels. */
    private static final float BOTTOM_MARGIN = 22F;
    /** Text size; also the unit the box's padding is measured in. */
    private static final float TEXT_SCALE = 1.35F;
    private static final float PAD_X = 14F;
    private static final float PAD_Y = 7F;
    /** Corner rounding, in GUI pixels, done by insetting the box at the four corners. */
    private static final int CORNER = 5;

    private static final float BACKGROUND_R = 0.10F;
    private static final float BACKGROUND_G = 0.10F;
    private static final float BACKGROUND_B = 0.11F;
    private static final float BACKGROUND_ALPHA = 0.72F;
    private static final float BORDER_R = 0.55F;
    private static final float BORDER_G = 0.55F;
    private static final float BORDER_B = 0.52F;
    private static final float BORDER_ALPHA = 0.55F;
    private static final float TEXT_R = 0.97F;
    private static final float TEXT_G = 0.97F;
    private static final float TEXT_B = 0.88F;

    /** How long the box takes to appear and to leave, in seconds. */
    private static final float FADE_SECONDS = 0.18F;

    /**
     * What kind of line is up. Ordered by standing: a {@link #REFUSAL} may replace
     * anything, a {@link #TUTORIAL} may not replace a live refusal.
     */
    public enum Kind {
        TUTORIAL,
        REFUSAL
    }

    /**
     * The client to draw through, or {@code null} for a box that only keeps time.
     *
     * <p>Nullable so the rules - one line at a time, a refusal outranking a tutorial, how
     * long a line stays up - can be tested without a window or a GL context. Drawing is the
     * only thing that needs either, and it is the only thing that reads this.
     */
    private final PvzceClient client;

    private String text = "";
    private Kind kind = Kind.TUTORIAL;
    private long shownNanos;
    /** Nanoseconds the line stays fully up; {@link LevelHint#PERSISTENT} means forever. */
    private long holdNanos = LevelHint.DEFAULT_DURATION_TICKS * 1_000_000_000L / 60L;

    public HintBox(PvzceClient client) {
        this.client = client;
    }

    /** True while a line is up, including its fade-out. */
    public boolean visible() {
        return !text.isEmpty() && alpha() > 0F;
    }

    /** The line currently up, or an empty string. For tests and diagnostics. */
    public String currentText() {
        return text;
    }

    /** Shows a tutorial line for the hint's own duration. */
    public void show(LevelHint hint) {
        if (hint == null || hint.text().isBlank()) {
            return;
        }
        long hold = hint.persistent()
                ? Long.MAX_VALUE / 4L
                : Math.max(1, hint.durationTicks()) * 1_000_000_000L / 60L;
        show(hint.text(), Kind.TUTORIAL, hold);
    }

    /** Shows a built-in refusal line; see {@link Kind}. */
    public void refuse(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        show(line, Kind.REFUSAL, LevelHint.DEFAULT_DURATION_TICKS * 1_000_000_000L / 60L);
    }

    private void show(String line, Kind newKind, long hold) {
        if (newKind == Kind.TUTORIAL && kind == Kind.REFUSAL && visible()) {
            // The player asked a question and got an answer; a lesson landing on top of
            // it would be answering something else.
            return;
        }
        this.text = line;
        this.kind = newKind;
        this.shownNanos = System.nanoTime();
        this.holdNanos = hold;
    }

    /** Takes the line down immediately, fading it out rather than cutting it. */
    public void hide() {
        if (text.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        long fadeNanos = (long) (FADE_SECONDS * 1_000_000_000L);
        long elapsed = now - shownNanos;
        if (elapsed < holdNanos - fadeNanos) {
            // Pull the deadline back so the fade-out starts now.
            holdNanos = Math.max(0L, elapsed);
            shownNanos = now;
        }
    }

    /** Drops everything, for a level that is being torn down. */
    public void clear() {
        text = "";
        shownNanos = 0L;
    }

    /**
     * 0..1 opacity, derived from the wall clock rather than accumulated per frame, so a
     * dropped frame cannot stretch the line's life.
     */
    public float alpha() {
        if (text.isEmpty() || shownNanos == 0L) {
            return 0F;
        }
        long fadeNanos = (long) (FADE_SECONDS * 1_000_000_000L);
        long elapsed = System.nanoTime() - shownNanos;
        if (elapsed < fadeNanos) {
            return MathUtil.clamp01(elapsed / (float) fadeNanos);
        }
        long remaining = holdNanos - elapsed;
        if (remaining > fadeNanos) {
            return 1F;
        }
        if (remaining <= 0L) {
            return 0F;
        }
        return MathUtil.clamp01(remaining / (float) fadeNanos);
    }

    /** Draws the box; a no-op once the line has finished fading, or with no client. */
    public void render() {
        float alpha = alpha();
        if (client == null || alpha <= 0F || text.isEmpty()) {
            return;
        }
        float textWidth = client.font().width(text, TEXT_SCALE);
        float boxWidth = textWidth + PAD_X * 2F;
        float boxHeight = client.font().lineHeight(TEXT_SCALE) + PAD_Y * 2F;
        float x = (client.guiWidth() - boxWidth) / 2F;
        float y = BOTTOM_MARGIN;

        roundedRect(x, y, boxWidth, boxHeight,
                BACKGROUND_R, BACKGROUND_G, BACKGROUND_B, BACKGROUND_ALPHA * alpha);
        // A one-pixel lighter edge, drawn as the difference between two rounded
        // rectangles. Without it the box disappears into the dark lawn at night.
        roundedRect(x, y, boxWidth, boxHeight, BORDER_R, BORDER_G, BORDER_B, BORDER_ALPHA * alpha);
        roundedRect(x + 1F, y + 1F, boxWidth - 2F, boxHeight - 2F,
                BACKGROUND_R, BACKGROUND_G, BACKGROUND_B, BACKGROUND_ALPHA * alpha);

        client.font().draw(text, x + PAD_X, y + PAD_Y, TEXT_SCALE,
                TEXT_R, TEXT_G, TEXT_B, alpha);
    }

    /**
     * A filled rectangle with its corners cut, drawn as three stacked rectangles.
     *
     * <p>The GUI has no rounded-rectangle primitive and this is the only place that wants
     * one: three {@code drawSolid} calls give the shape with no texture, no shader and no
     * new asset, which is what the original's own art is standing in for. The corners are
     * stair-stepped in {@link #CORNER} steps - at this size that reads as a soft corner
     * rather than as a diagonal, and it costs one draw call per step instead of per pixel.
     */
    private void roundedRect(float x, float y, float width, float height,
                             float r, float g, float b, float alpha) {
        if (width <= 0F || height <= 0F || alpha <= 0F) {
            return;
        }
        float z = RENDER_Z;
        // Top and bottom bands keep full width; the middle band is inset by the step.
        client.drawSolid(x, y + CORNER, width, Math.max(0F, height - CORNER * 2F), z, r, g, b, alpha);
        for (int step = 0; step < CORNER; step++) {
            float inset = CORNER - step;
            client.drawSolid(x + inset, y + step, width - inset * 2F, 1F, z, r, g, b, alpha);
            client.drawSolid(x + inset, y + height - step - 1F, width - inset * 2F, 1F, z, r, g, b, alpha);
        }
    }

    /**
     * Draw order: above the board and the HUD, below the card bar and any dialog.
     *
     * <p>The HUD's own layers top out around 0.4 and the card bar sits above this, because
     * a hint must never be drawn over the cards the player is being told about.
     */
    private static final float RENDER_Z = 0.45F;

    /**
     * The two built-in refusal lines, in the player's language.
     *
     * <p>Resolved per call rather than kept in constants: the language files load when the
     * client starts and again on {@code /reload}, so a {@code static final} captured at
     * class-load would freeze whichever locale happened to be up first.
     */
    public static final class Refusal {
        public static String cooldown() {
            return GuiLang.raw("pvzce.cooldown", "冷却中");
        }

        public static String notEnough() {
            return GuiLang.raw("pvzce.not_enough", "资源不足");
        }

        private Refusal() {
        }
    }
}
