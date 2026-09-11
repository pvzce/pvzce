package com.pvzce.client.animation;

import com.pvzce.api.util.Identifier;

import java.util.List;

/**
 * A named flipbook clip: ordered frame identifiers plus per-frame delays.
 *
 * <p>Timeline maths (wrap, frame lookup, delay defaults) lives in {@link Timeline};
 * this record only holds the authored data. It used to carry its own loop wrap in
 * microsecond integers, a second copy of the "one delay means all frames" rule and
 * a third copy of the frame-start summation.
 */
public record FlipbookClip(
        List<Identifier> frames,
        float[] delays,
        boolean loop,
        OnEnd onEnd,
        String next,
        float transition,
        List<AnimationCue.Sound> soundCues,
        List<AnimationCue.Particle> particleCues
) implements AnimationClip {
    public FlipbookClip {
        frames = List.copyOf(frames);
        delays = delays.clone();
        soundCues = List.copyOf(soundCues);
        particleCues = List.copyOf(particleCues);
        next = next == null ? "" : next;
    }

    @Override
    public float duration() {
        return Timeline.totalDuration(delays, frames.size());
    }

    public float delay(int frame) {
        return Timeline.delayAt(delays, frame);
    }

    /** Sampled frame index for an absolute clip time (loop-aware). */
    public int frameIndex(double time) {
        return Timeline.frameAt(delays, frames.size(), time, loop);
    }

    /** Start time of a frame in the clip timeline. */
    public float frameStart(int frame) {
        return Timeline.frameStart(delays, frame);
    }

    public Identifier frame(int index) {
        if (index < 0 || index >= frames.size()) {
            return null;
        }
        return frames.get(index);
    }
}
