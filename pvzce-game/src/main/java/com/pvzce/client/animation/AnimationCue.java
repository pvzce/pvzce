package com.pvzce.client.animation;

import com.pvzce.api.util.Identifier;

/** Immutable keyframe event payloads shared by both animation backends. */
public final class AnimationCue {
    private AnimationCue() {
    }

    public record Sound(float time, Identifier effect, float volume, float pitch) {
    }

    /**
     * A particle event. {@code locator} names a controller bone (or is empty
     * for the animation anchor); flipbook animations ignore locator names and
     * spawn at the frame anchor.
     */
    public record Particle(float time, Identifier effect, String locator) {
    }
}
