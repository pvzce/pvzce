package com.pvzce.client.animation;

import java.util.Map;

/** Controller resource: one 2D model plus named clips. */
public record ControllerFile(
        ControllerModel model,
        Map<String, ControllerClip> clips
) implements AnimationFile {
    public ControllerFile {
        clips = Map.copyOf(clips);
    }

    @Override
    public AnimationType type() {
        return AnimationType.CONTROLLER;
    }

    @Override
    public Map<String, ControllerClip> clips() {
        return clips;
    }
}
