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
