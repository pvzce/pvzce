package com.pvzce.client.animation;

import java.util.Locale;

/**
 * How a controller part is composited into the world.
 *
 * <p>The original reanim format carries no blend flag - PopCap's renderer chose it in
 * code, per sprite - so the information only exists in what a sprite <em>is</em>. A glow
 * is light and has to be added; everything else is paint and is drawn source-over. The
 * converter writes this field from a per-part table, which is the only place that
 * knowledge can live.
 *
 * <p>It matters most where an animation fakes a soft gradient: a 117px radial glow at
 * {@code alpha 0.5} drawn source-over is a flat grey disc that hides what is behind it,
 * while the same quad added is the halo it was authored as. That is also why the coin's
 * glow used to be cut from the art entirely.
 */
public enum BlendMode {
    /** Source-over alpha blending, the default for every part. */
    NORMAL,
    /** Additive: light rather than paint. */
    ADD;

    public static BlendMode parse(String value) {
        if (value == null) {
            return NORMAL;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "add", "additive", "plus", "glow" -> ADD;
            default -> NORMAL;
        };
    }
}
