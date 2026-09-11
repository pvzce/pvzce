package com.pvzce.client.animation;

/**
 * Handle returned by {@link com.pvzce.client.api.Animatable#playAnimation(String)}.
 * Handles stay valid for the playback they were created for; querying one
 * after its target switched to another animation simply reports inactive.
 */
public interface AnimationHandle {
    /** No-op handle used when a target has no animation resource attached. */
    AnimationHandle NONE = new AnimationHandle() {
        @Override
        public String animation() {
            return "";
        }

        @Override
        public boolean isActive() {
            return false;
        }

        @Override
        public boolean isFinished() {
            return true;
        }

        @Override
        public float progress() {
            return 1F;
        }

        @Override
        public void restart() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void setSpeed(float speed) {
        }
    };

    /** Currently active animation name, or empty for {@link #NONE}. */
    String animation();

    /** True while this handle is still the target's active playback. */
    boolean isActive();

    /** True once a non-looping animation has completed. */
    boolean isFinished();

    /** Normalized 0..1 progress through the clip; 1 for finished clips. */
    float progress();

    /** Restarts the clip from time zero without changing the active state. */
    void restart();

    /** Stops the target's playback. */
    void stop();

    /** Playback speed multiplier; 1 is normal game/wall time speed. */
    void setSpeed(float speed);
}
