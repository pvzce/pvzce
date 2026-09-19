package com.pvzce.client.animation;

import java.util.List;

/**
 * A single named animation clip. A resource file may contain several clips
 * (for example {@code idle} and {@code shoot}).
 */
public sealed interface AnimationClip permits FlipbookClip, ControllerClip {
    /** Clip length in seconds. */
    float duration();

    boolean loop();

    OnEnd onEnd();

    /** Clip name to switch to when {@link OnEnd#NEXT} is used. */
    String next();

    /** Cross-fade duration in seconds when this clip becomes active. */
    float transition();

    /**
     * Authored playback rate: how many clip seconds pass per world second.
     *
     * <p>A reanim file has one frame rate for every track in it, but the original game
     * then played individual actions at speeds of its own - the zombie's death runs at
     * roughly double its 12fps authoring rate and its bite at still another. Without a
     * per-clip number those actions can only be ported at the file's rate, which made a
     * death take 3.25 s where the original takes about 1.6 s and a bite cycle 3.33 s
     * where the original takes about 2 s.
     *
     * <p>Defaults to {@code 1}, so a clip that declares nothing plays at authoring speed.
     */
    float rate();

    /**
     * The ground speed, in cells per second, that this clip was authored for.
     *
     * <p>Only meaningful for a locomotion clip. A walk cycle is drawn so that the planted
     * foot is stationary while the body passes over it, which makes the cycle's own
     * duration a statement about how fast the creature was travelling: 47 frames at 12fps
     * is one cell of travel in 3.9167 s, so the art assumes about 0.255 cells/s. A
     * creature moving at another speed has to be played at the ratio of the two, or its
     * feet slide - the buckethead and the conehead walk at 0.18 cells/s against the plain
     * zombie's 0.23, which is an 18% slide before this field existed.
     *
     * <p>{@code 0} - the default - means "this clip is not about travel", and the playback
     * keeps {@link #rate()} untouched.
     */
    float referenceSpeed();

    List<AnimationCue.Sound> soundCues();

    List<AnimationCue.Particle> particleCues();

    enum OnEnd {
        HOLD,
        IDLE,
        NEXT;

        public static OnEnd parse(String value) {
            if (value == null) {
                return HOLD;
            }
            return switch (value.toLowerCase(java.util.Locale.ROOT)) {
                case "idle" -> IDLE;
                case "next" -> NEXT;
                default -> HOLD;
            };
        }
    }
}
