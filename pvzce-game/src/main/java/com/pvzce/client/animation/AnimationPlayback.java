package com.pvzce.client.animation;

import com.pvzce.client.PvzceClient;
import com.pvzce.client.api.Animatable;

import java.util.List;

/** Shared timing, transition, event and lifecycle logic for both backends. */
public abstract class AnimationPlayback {
    /** Authored rates are clamped to this range so a data typo cannot freeze or race a clip. */
    public static final float MIN_RATE = 0.01F;
    public static final float MAX_RATE = 20F;
    /** How slow a locomotion clip may be played when the target is barely moving. */
    private static final float MIN_LOCOMOTION_SCALE = 0.05F;

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
    /**
     * Whether the clip's clock is held still.
     *
     * <p>The ice-shroom's freeze: a zombie held by the cold keeps the pose it was in rather than
     * walking its walk cycle on the spot. Set from the entity's synced state; the clock is not
     * reset on release, so the clip carries on from the frame it was stopped on.
     */
    protected boolean paused;
    private double pausedAt;
    /**
     * The caller's own multiplier, from {@link #setSpeed}.
     *
     * <p>Kept apart from the clip's authored {@link AnimationClip#rate()} so a caller that
     * asks for "1.5x" gets 1.5x of what the data says this action runs at, not 1.5x of the
     * file's frame rate. Folding the two into one field meant the second writer silently
     * erased the first.
     */
    protected float speed = 1F;
    /**
     * The locomotion factor, from {@link #setLocomotionScale}.
     *
     * <p>1 until the manager measures the target's ground speed against
     * {@link AnimationClip#referenceSpeed()}; see that method for why a walk cycle needs it.
     */
    protected float locomotionScale = 1F;
    protected boolean finished;
    protected boolean stopped;
    protected boolean endApplied;
    /**
     * Whether this playback's art is mirrored about its anchor.
     *
     * <p>Set by the manager from the entity's own state - a charmed zombie walks the other way,
     * so it has to face the other way - and read by both backends. A flag on the playback rather
     * than a parameter of {@code render} because it is a property of *what is being drawn*, and
     * because the two backends would otherwise both need the extra argument threaded through
     * every caller for one entity state.
     */
    protected boolean flipX;
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

    /** Clamps an authored rate into the range a playback can survive. */
    public static float sanitizeRate(float rate) {
        if (Float.isNaN(rate) || rate <= 0F) {
            return 1F;
        }
        return Math.max(MIN_RATE, Math.min(MAX_RATE, rate));
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

    /** Mirrors this playback's art about its anchor; see {@link #flipX}. */
    public final void setFlipX(boolean value) {
        this.flipX = value;
    }

    public final boolean flipX() {
        return flipX;
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

    /** Freezes or resumes the clip's clock; see {@link #paused}. */
    public final void setPaused(boolean value) {
        if (paused == value) {
            return;
        }
        double now = manager.now();
        if (value) {
            pausedAt = now;
        } else {
            // The clock resumes where it stopped: shift the anchor forward by however long the
            // pause lasted, so the clip continues from the frame it was held on.
            startGameSeconds += now - pausedAt;
        }
        paused = value;
    }

    public final boolean paused() {
        return paused;
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
        speed = clamped;
        startGameSeconds = now - current / effectiveSpeed();
        previousSampleTime = current;
    }

    /**
     * Re-anchors the playback when the locomotion factor changes.
     *
     * <p>Same shape as {@link #setSpeed} and for the same reason: the clip position must
     * not move when the speed does, or a walk that speeds up mid-stride would jump. Called
     * once per frame by the manager for clips that declare
     * {@link AnimationClip#referenceSpeed()}; a clip that does not is never touched.
     */
    final void setLocomotionScale(float scale) {
        float clamped = Math.max(MIN_LOCOMOTION_SCALE, Math.min(4F, scale));
        if (Math.abs(clamped - locomotionScale) < 1.0E-4F) {
            return;
        }
        double now = manager.now();
        double current = localTime(now);
        locomotionScale = clamped;
        startGameSeconds = now - current / effectiveSpeed();
        previousSampleTime = current;
    }

    /** Clip seconds per world second: the data's rate times the caller's and the ground's. */
    public final float effectiveSpeed() {
        return sanitizeRate(clip.rate()) * speed * locomotionScale;
    }

    public final float locomotionScale() {
        return locomotionScale;
    }

    /** Last measured ground speed in cells per second; 0 for anything that cannot move. */
    public final float measuredSpeed() {
        return measuredSpeed;
    }

    /** Previous sample of the target's drawn position, and when it was taken. */
    private double lastMeasureNanos;
    private float lastMeasureX;
    private float lastMeasureY;
    private boolean measured;
    private float measuredSpeed;

    /**
     * Derives the playback's speed from how fast the target is actually travelling.
     *
     * <p>The measurement is the target's <em>drawn</em> position, not the server's: that is
     * the position the art is anchored to, so it is the one whose motion the feet have to
     * match. It is also already interpolated between the 20 Hz server updates, which means
     * this reads a continuous velocity instead of a five-frames-still-then-a-jump one.
     *
     * <p>Sampled on the wall clock rather than the game clock for the same reason the drawn
     * position is: a frozen server stops the game clock but not the slide, and dividing a
     * moving distance by a stopped time would report an infinite speed.
     *
     * <p>Anything slower than {@link #STATIONARY_CELLS_PER_SECOND} counts as standing still
     * and plays at authoring rate; the floor on the scale is what stops a walk cycle from
     * freezing mid-stride when an entity is blocked by a plant it is eating.
     */
    final void measureLocomotion(double now) {
        if (clip.referenceSpeed() <= 0F) {
            // Not a locomotion clip: leave the scale at whatever it was, which is 1 for a
            // clip that never declares a reference speed, and never touch the time base.
            return;
        }
        double nanos = System.nanoTime();
        float speed = 0F;
        if (target instanceof com.pvzce.client.api.MovingTarget mover) {
            float x = mover.drawnX();
            float y = mover.drawnY();
            if (measured) {
                double seconds = (nanos - lastMeasureNanos) / 1_000_000_000D;
                if (seconds > 1.0E-4D) {
                    speed = (float) (Math.hypot(x - lastMeasureX, y - lastMeasureY) / seconds);
                }
            }
            lastMeasureX = x;
            lastMeasureY = y;
        }
        lastMeasureNanos = nanos;
        measured = true;
        measuredSpeed = speed;
        if (Boolean.getBoolean("pvzce.traceLocomotion")) {
        }
        setLocomotionScale(speed <= STATIONARY_CELLS_PER_SECOND ? 1F : speed / clip.referenceSpeed());
    }

    /** Below this the target is standing still, whatever tiny drift the mirror shows. */
    public static final float STATIONARY_CELLS_PER_SECOND = 0.02F;

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
        double t = ((paused ? pausedAt : now) - startGameSeconds) * effectiveSpeed();
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

    /**
     * Renders the playback at the target's current world anchor.
     *
     * <p>Two scale factors rather than one, because the caller has two different things to
     * say: {@code xScale} is the world's aspect correction (a PvZ cell is 80 wide and 100
     * tall, so square art has to be widened), and {@code yScale} is the content's own size.
     * A single factor could only express one of them applied to both axes, which is how a
     * drop ended up fitting its width to a target and keeping its authored height.
     */
    public abstract void render(PvzceClient client, float anchorX, float anchorY, float baseZ,
                                float xScale, float yScale);

    /** World-space event position for a controller bone locator. */
    public abstract float[] eventPosition(String locator);

    protected void onRestart(double now) {
    }
}
