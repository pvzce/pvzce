package com.pvzce.client.animation;

import com.pvzce.client.ClientEntity;

/** Per-entity animation slot; injected by {@code ClientLevel} on spawn. */
public final class AnimationComponent {
    private final ClientEntity owner;
    private AnimationManager manager;
    private String requestedState = "";

    public AnimationComponent(ClientEntity owner) {
        this.owner = owner;
    }

    public void attach(AnimationManager manager) {
        this.manager = manager;
    }

    public AnimationHandle play(String state) {
        if (manager == null || state == null || state.isBlank()) {
            return AnimationHandle.NONE;
        }
        requestedState = state;
        return manager.play(owner, state);
    }

    public void stop() {
        if (manager != null) {
            manager.stop(owner);
        }
        requestedState = "";
    }

    public String requestedState() {
        return requestedState;
    }

    public boolean hasManager() {
        return manager != null;
    }
}
