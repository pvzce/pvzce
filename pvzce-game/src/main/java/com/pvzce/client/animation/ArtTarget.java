package com.pvzce.client.animation;

import com.pvzce.api.util.Identifier;
import com.pvzce.client.api.Animatable;

/**
 * An animation target that is not an entity: a level mechanic's prop, drawn at coordinates
 * its owner computes.
 *
 * <p>{@link AnimationManager} resolves an entity's animation file from its definition id (see
 * {@code EntityArt}); anything that is not content - a lawn mower, which is a level mechanic
 * and has no registry entry - names its file outright instead. Everything else about playback
 * is identical, which is the point: the same controller models, the same clock, the same
 * {@code render} call, so a mechanic does not need a second renderer.
 *
 * <p>The owner keeps the target alive for as long as it draws it, and calls
 * {@link #stopAnimation()} when it stops; playbacks are keyed by target, so a target that is
 * dropped without that would leave its playback behind in the manager.
 */
public final class ArtTarget implements Animatable {
    private final Identifier fileId;
    private AnimationManager manager;
    private AnimationHandle handle = AnimationHandle.NONE;
    private String requestedState = "";

    public ArtTarget(Identifier fileId) {
        this.fileId = fileId;
    }

    /** The animation file this target plays; {@code assets/<ns>/animations/<path>.json}. */
    public Identifier fileId() {
        return fileId;
    }

    public void attach(AnimationManager manager) {
        this.manager = manager;
    }

    /** Requests a state (idempotent, like every other playback). */
    public AnimationHandle play(String state) {
        if (manager == null || state == null || state.isBlank()) {
            return AnimationHandle.NONE;
        }
        requestedState = state;
        handle = manager.play(this, state);
        return handle;
    }

    @Override
    public AnimationHandle playAnimation(String animation) {
        return play(animation);
    }

    @Override
    public void stopAnimation() {
        requestedState = "";
        handle = AnimationHandle.NONE;
        if (manager != null) {
            manager.stop(this);
        }
    }

    @Override
    public String currentAnimation() {
        return requestedState;
    }

    /** The handle of the last request; {@link AnimationHandle#NONE} before the first one. */
    public AnimationHandle handle() {
        return handle;
    }
}
