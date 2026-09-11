package com.pvzce.client.animation;

import java.util.Locale;

/** Keyframe interpolation modes shared by controller clips. */
public enum Easing {
    LINEAR {
        @Override
        public float apply(float t) {
            return t;
        }
    },
    STEP {
        @Override
        public float apply(float t) {
            return 0F;
        }
    },
    EASE_IN {
        @Override
        public float apply(float t) {
            return t * t;
        }
    },
    EASE_OUT {
        @Override
        public float apply(float t) {
            return 1F - (1F - t) * (1F - t);
        }
    },
    EASE_IN_OUT {
        @Override
        public float apply(float t) {
            return t < 0.5F ? 2F * t * t : 1F - 2F * (1F - t) * (1F - t);
        }
    };

    public abstract float apply(float t);

    public static Easing parse(String value) {
        if (value == null) {
            return LINEAR;
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return switch (normalized) {
            case "step", "constant" -> STEP;
            case "easein", "easeinquad" -> EASE_IN;
            case "easeout", "easeoutquad" -> EASE_OUT;
            case "easeinout", "easeinoutquad", "easeinoutsine", "smooth" -> EASE_IN_OUT;
            default -> LINEAR;
        };
    }
}
