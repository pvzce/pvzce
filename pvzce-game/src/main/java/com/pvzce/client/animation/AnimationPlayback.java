package com.pvzce.client.animation;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.api.Animatable;

import java.util.List;

/** Shared timing, transition, event and lifecycle logic for both backends. */
public abstract class AnimationPlayback {
    protected final AnimationManager manager;
    protected final Animatable target;
    protected final AnimationFile file;
    protected final AnimationClip clip;
    protected final String requestedState;
    protected final String activeName;
    protected final AnimationPlayback previous;
    protected final float transitionDuration;
    protected final double transitionStart;

    protected double startGameSeconds;
    protected double previousSampleTime = -0.0001D;
    protected float speed = 1F;
    protected boolean finished;
    protected boolean stopped;
    protected boolean endApplied;
    private boolean firstEventUpdate = true;

    protected AnimationPlayback(AnimationManager manager, Animatable target, AnimationFile file,
                                AnimationClip clip, String requestedState, String activeName,
                                AnimationPlayback previous, double now) {
        this.manager = manager;
        this.target = target;
        this.file = file;
        this.clip = clip;
        this.requestedState = requestedState;
        this.activeName = activeName;
        this.previous = previous;
        this.startGameSeconds = now;
        this.transitionStart = now;
        float requestedTransition = previous == null ? 0F : Math.max(0F, clip.transition());
        this.transitionDuration = Math.min(requestedTransition, Math.max(0.001F, clip.duration()));
    }

    public final String requestedState() {
        return requestedState;
    }

    public final String activeName() {
        return activeName;
    }

    public final AnimationFile file() {
        return file;
    }

    public final AnimationClip clip() {
        return clip;
    }

    public final boolean isStopped() {
        return stopped;
    }

    public final boolean isFinished() {
        return finished;
    }

    public final float progress() {
        if (clip.duration() <= 0F) {
            return finished ? 1F : 0F;
        }
        return Math.max(0F, Math.min(1F, (float) (localTime(manager.now()) / clip.duration())));
    }

    public final void restart() {
        double now = manager.now();
        startGameSeconds = now;
        previousSampleTime = -0.0001D;
        finished = false;
        endApplied = false;
        firstEventUpdate = true;
        onRestart(now);
    }

    public final void stop() {
        if (!stopped) {
            stopped = true;
            finished = true;
            manager.onPlaybackStopped(this);
        }
    }

    public final void setSpeed(float newSpeed) {
        float clamped = Math.max(0.001F, Math.min(100F, newSpeed));
        if (Math.abs(clamped - speed) < 1.0E-6F) {
            return;
        }
        double now = manager.now();
        double current = localTime(now);
        startGameSeconds = now - current / clamped;
        previousSampleTime = current;
        speed = clamped;
    }

    public final void update(double now) {
        if (stopped) {
            return;
        }
        double time = localTime(now);
        fireEvents(previousSampleTime, time);
        if (!endApplied && clip.duration() <= 0F) {
            endApplied = true;
            applyEnd(now);
        } else if (!clip.loop() && clip.duration() > 0F && time >= clip.duration() && !endApplied) {
            endApplied = true;
            applyEnd(now);
        }
        previousSampleTime = clip.loop() ? time : Math.min(time, Math.max(0F, clip.duration()));
    }

    protected void applyEnd(double now) {
        switch (clip.onEnd()) {
            case HOLD -> finished = true;
            case IDLE -> {
                if ("idle".equals(activeName) || file.clip("idle").isEmpty()) {
                    finished = true;
                } else {
                    AnimationPlayback switched = manager.switchClip(target, "idle", requestedState, now);
                    if (switched == null) {
                        finished = true;
                    }
                }
            }
            case NEXT -> {
                String next = clip.next();
                if (next.isBlank() || next.equals(activeName) || file.clip(next).isEmpty()) {
                    finished = true;
                } else {
                    AnimationPlayback switched = manager.switchClip(target, next, requestedState, now);
                    if (switched == null) {
                        finished = true;
                    }
                }
            }
        }
    }

    protected final double localTime(double now) {
        double t = (now - startGameSeconds) * speed;
        return Math.max(0D, t);
    }

    protected final float transitionBlend(double now) {
        if (previous == null || transitionDuration <= 0F) {
            return 1F;
        }
        return Math.max(0F, Math.min(1F, (float) ((now - transitionStart) / transitionDuration)));
    }

    private void fireEvents(double from, double to) {
        if (to <= from || clip.duration() <= 0F) {
            firstEventUpdate = false;
            return;
        }
        boolean includeStart = firstEventUpdate;
        firstEventUpdate = false;
        if (clip.loop()) {
            double duration = clip.duration();
            // Walk from the start of the cycle containing `from`: the position maths
            // is Timeline's, so a cue exactly on a loop boundary fires once per cycle.
            double start = Math.max(0D, from);
            double cycleStart = start - Timeline.wrap(start, (float) duration, true);
            int guard = 0;
            while (cycleStart < to && guard++ < 4096) {
                double cycleEnd = cycleStart + duration;
                fireRange(Math.max(start, cycleStart), Math.min(to, cycleEnd), cycleStart,
                        includeStart && cycleStart <= 0D);
                cycleStart = cycleEnd;
            }
        } else {
            fireRange(from, Math.min(to, clip.duration()), 0D, includeStart);
        }
    }

    private void fireRange(double from, double to, double offset, boolean includeStart) {
        List<AnimationCue.Sound> sounds = clip.soundCues();
        for (AnimationCue.Sound cue : sounds) {
            double time = offset + cue.time();
            if (time <= to && (time > from || (includeStart && time == from))) {
                manager.fireSound(cue);
            }
        }
        List<AnimationCue.Particle> particles = clip.particleCues();
        for (AnimationCue.Particle cue : particles) {
            double time = offset + cue.time();
            if (time <= to && (time > from || (includeStart && time == from))) {
                manager.fireParticle(cue, this);
            }
        }
    }

    /** Renders the playback at the target's current world anchor. */
    public abstract void render(PvzceClient client, float anchorX, float anchorY, float baseZ,
                                float xScale);

    /** World-space event position for a controller bone locator. */
    public abstract float[] eventPosition(String locator);

    protected void onRestart(double now) {
    }
}
