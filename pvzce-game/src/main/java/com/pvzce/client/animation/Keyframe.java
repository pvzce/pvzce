package com.pvzce.client.animation;

/** One keyframe value at a clip-local time in seconds. */
public record Keyframe<T>(float time, T value, Easing easing) {
}
