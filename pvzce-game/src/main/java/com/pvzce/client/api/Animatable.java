package com.pvzce.client.api;

import com.pvzce.client.animation.AnimationHandle;

/**
 * A client-side visual target that can play named animations.
 *
 * <p>Implementations are intentionally tiny: the actual resource resolution,
 * clocking and rendering live in {@code com.pvzce.client.animation}. This is
 * the single entry point callers should use, regardless of whether the
 * backing resource is a flipbook or a 2D controller animation.</p>
 */
public interface Animatable {
    /**
     * Requests a logical animation state (for example {@code idle} or
     * {@code walk}). Repeated calls with the same state are idempotent.
     */
    AnimationHandle playAnimation(String animation);

    /** Stops the current animation and releases the target's playback slot. */
    void stopAnimation();

    /** The last logical state requested through {@link #playAnimation(String)}. */
    String currentAnimation();
}
