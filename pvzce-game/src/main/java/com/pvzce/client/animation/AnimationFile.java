package com.pvzce.client.animation;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Parsed animation resource file: one per entity (flipbook or controller). */
public sealed interface AnimationFile permits FlipbookFile, ControllerFile {
    AnimationType type();

    Map<String, ? extends AnimationClip> clips();

    default Optional<AnimationClip> clip(String name) {
        return Optional.ofNullable(clips().get(name));
    }

    default Set<String> clipNames() {
        return clips().keySet();
    }

    /** Backend kind as declared in the JSON root. */
    enum AnimationType {
        FLIPBOOK,
        CONTROLLER;

        public static Optional<AnimationType> parse(String value) {
            if (value == null) {
                return Optional.empty();
            }
            return switch (value.toLowerCase(java.util.Locale.ROOT)) {
                case "flipbook" -> Optional.of(FLIPBOOK);
                case "controller" -> Optional.of(CONTROLLER);
                default -> Optional.empty();
            };
        }
    }
}
