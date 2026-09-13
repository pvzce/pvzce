package com.pvzce.client.animation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.content.AnimationBindings;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.ClientEntity;
import com.pvzce.client.ClientLevel;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.client.PvzceClient;
import com.pvzce.client.renderer.EntityVisuals;
import com.pvzce.client.api.Animatable;
import com.pvzce.common.core.EntityArt;
import com.pvzce.common.resource.PvzceResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side animation registry, clock bridge and render dispatcher.
 *
 * <p>Callers only use {@link Animatable#playAnimation(String)}. The manager
 * resolves the target's resource by state override, whole-entity override or
 * the {@code assets/<ns>/animations/<def_path>.json} convention, detects the
 * backend from the parsed file and advances all playbacks from the shared
 * client game clock.</p>
 */
public final class AnimationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Animation");

    private final PvzceClient client;
    private final ClientLevel level;
    private final PvzceResourceManager resources;

    private final Map<Identifier, AnimationFile> files = new ConcurrentHashMap<>();
    private final Set<Identifier> missing = ConcurrentHashMap.newKeySet();
    /** Files that exist but failed to parse; retried after {@link #invalidate()}. */
    private final Set<Identifier> broken = ConcurrentHashMap.newKeySet();
    private final Map<Animatable, AnimationPlayback> playbacks = new IdentityHashMap<>();

    public AnimationManager(PvzceClient client, ClientLevel level, PvzceResourceManager resources) {
        this.client = client;
        this.level = level;
        this.resources = resources;
    }

    /** Current world animation time in game seconds (60 game ticks = 1 second). */
    public double now() {
        return level == null ? 0D : level.gameSeconds();
    }

    /** Anchor for world entities; UI targets can override later. */
    public float[] anchor(Animatable target) {
        if (!(target instanceof ClientEntity entity)) {
            return new float[]{0F, 0F};
        }
        float lift = com.pvzce.client.renderer.EntityVisuals.anchorLift(entity.kind());
        return new float[]{entity.cellX(), entity.cellY() + entity.height() - lift};
    }

    public float baseZ(Animatable target) {
        if (!(target instanceof ClientEntity entity)) {
            return com.pvzce.client.renderer.EntityVisuals.baseZ("");
        }
        return com.pvzce.client.renderer.EntityVisuals.baseZ(entity.kind());
    }

    /** Unified playback entry point. Repeated states are idempotent. */
    public AnimationHandle play(Animatable target, String state) {
        if (target == null || state == null || state.isBlank()) {
            return AnimationHandle.NONE;
        }
        AnimationPlayback current = playbacks.get(target);
        if (current != null && !current.isStopped() && isAlreadyPlaying(current, state)) {
            return handle(target, current);
        }

        Optional<AnimationFile> file = fileFor(target, state);
        if (file.isEmpty()) {
            stop(target);
            return AnimationHandle.NONE;
        }
        AnimationFile animationFile = file.get();
        AnimationClip clip = animationFile.clip(state).orElse(null);
        String activeName = state;
        if (clip == null) {
            activeName = "idle";
            clip = animationFile.clip("idle").orElse(null);
        }
        if (clip == null) {
            LOGGER.warn("Animation {} has no '{}' or 'idle' clip for {}", animationFile.type(), state, target);
            stop(target);
            return AnimationHandle.NONE;
        }

        AnimationPlayback previous = current != null && !current.isStopped() ? current : null;
        double now = now();
        AnimationPlayback playback = create(target, animationFile, clip, state, activeName, previous, now);
        playbacks.put(target, playback);
        return handle(target, playback);
    }

    /**
     * True when the request is already satisfied.
     *
     * <p>Deduplicating on {@code requestedState} alone made a one-shot clip
     * un-retriggerable: when the clip ends it switches itself to {@code idle} while
     * keeping {@code requestedState == "shoot"}, so the next {@code play("shoot")}
     * found a matching requested state and returned the stale idle playback. The
     * server publishes {@code "shoot"} and then {@code "idle"} for a single tick
     * each, so whether the animation ever replayed depended on a client frame
     * landing inside that one 16.7ms tick. Comparing against the active clip lets a
     * finished one-shot restart, while a looping clip that is already running is
     * still left alone.
     */
    private static boolean isAlreadyPlaying(AnimationPlayback current, String state) {
        if (!state.equals(current.requestedState())) {
            return false;
        }
        if (state.equals(current.activeName()) && current.clip().loop()) {
            return true;
        }
        // A one-shot that already handed over (to idle or to its `next` clip) has to
        // be allowed to start again.
        return state.equals(current.activeName()) && !current.isFinished();
    }

    private AnimationPlayback create(Animatable target, AnimationFile file, AnimationClip clip,
                                     String requestedState, String activeName,
                                     AnimationPlayback previous, double now) {
        if (file instanceof FlipbookFile flipbook && clip instanceof FlipbookClip flip) {
            return new FlipbookPlayback(this, target, flipbook, flip, requestedState, activeName, previous, now);
        }
        if (file instanceof ControllerFile controller && clip instanceof ControllerClip controllerClip) {
            return new ControllerPlayback(this, target, controller, controllerClip, requestedState, activeName, previous, now);
        }
        throw new IllegalArgumentException("Animation backend mismatch for " + requestedState);
    }

    /** Internal on_end / next transition; preserves the requested state. */
    AnimationPlayback switchClip(Animatable target, String activeName, String requestedState, double now) {
        AnimationPlayback current = playbacks.get(target);
        if (current == null || current.isStopped()) {
            return null;
        }
        Optional<AnimationClip> clip = current.file().clip(activeName);
        if (clip.isEmpty()) {
            return null;
        }
        AnimationPlayback playback = create(target, current.file(), clip.get(), requestedState, activeName, current, now);
        playbacks.put(target, playback);
        return playback;
    }

    public void stop(Animatable target) {
        AnimationPlayback playback = playbacks.remove(target);
        if (playback != null) {
            playback.stop();
        }
    }

    public AnimationPlayback playback(Animatable target) {
        return playbacks.get(target);
    }

    /** Drops every playback (level change) but keeps the parsed file cache. */
    public void clear() {
        playbacks.clear();
    }

    /** Drops every playback for a target that is going away (a closed preview screen). */
    public void release(Animatable target) {
        stop(target);
    }

    /** Advances all active playbacks from the current shared clock. */
    public void tick() {
        double now = now();
        List<Map.Entry<Animatable, AnimationPlayback>> snapshot = new ArrayList<>(playbacks.entrySet());
        for (Map.Entry<Animatable, AnimationPlayback> entry : snapshot) {
            Animatable target = entry.getKey();
            AnimationPlayback playback = entry.getValue();
            if (playbacks.get(target) != playback) {
                continue;
            }
            playback.update(now);
            if (playback.isStopped() && playbacks.get(target) == playback) {
                playbacks.remove(target);
            }
        }
    }

    /** Returns true when this entity was drawn by an animation resource. */
    public boolean render(ClientEntity entity) {
        AnimationPlayback playback = playbacks.get(entity);
        if (playback == null || playback.isStopped()) {
            return false;
        }
        float[] anchor = anchor(entity);
        float vs = visualScale(entity);
        if (EntityKind.RESOURCE.equals(entity.kind())) {
            client.pushEntityTint(EntityVisuals.DROP_TINT);
        }
        try {
            playback.render(client, anchor[0], anchor[1], baseZ(entity), xScaleFor(entity, vs));
        } finally {
            if (EntityKind.RESOURCE.equals(entity.kind())) {
                client.popEntityTint();
            }
        }
        return true;
    }

    /**
     * The horizontal scale this entity's animation part geometry is drawn with.
     *
     * <p>Two factors, and only two:
     *
     * <ol>
     *   <li>the board's own aspect correction - everything but a drop is drawn with it,
     *       because the projection maps a world cell to {@code unitY / unitX} (1.25, the
     *       original's 80x100 cell) and plant, zombie and tool art is authored to be
     *       widened by that same factor. A drop is drawn <em>without</em> it because its
     *       geometry already carries it: {@link #visualScale} fits a drop to one target
     *       size in both axes, so applying the board factor on top stretched the sun and
     *       the coins sideways - a circle authored in the art arrived as an ellipse 1.7x
     *       wider than it was tall;</li>
     *   <li>{@code render_scale} from the entity's own definition
     *       ({@link EntityArt#renderScale}), which is the per-content size knob and is
     *       applied to both axes so the shape is never changed by it.</li>
     * </ol>
     */
    private float xScaleFor(ClientEntity entity, float visualScale) {
        float board = EntityKind.RESOURCE.equals(entity.kind())
                ? 1F
                : client.spriteXScale();
        return board * visualScale * renderScale(entity);
    }

    /** The definition's {@code render_scale}, or 1 for anything that declares none. */
    float renderScale(ClientEntity entity) {
        return EntityArt.renderScale(entity.defId());
    }

    /**
     * How much bigger this entity should be drawn than its animation file says.
     *
     * <p>Without this, resizing a drop by editing {@link EntityVisuals} does nothing:
     * anything with an animation is drawn from the sizes baked into that file, and
     * {@code EntityVisuals} only applies to the sprite fallback. The sun's animation is
     * 0.56 cells wide, which is what made it look like a speck no matter what the visual
     * table said.
     *
     * <p>One factor, taken from the art's <em>larger</em> dimension, so the shape the art
     * drew is the shape the lawn shows.
     *
     * <p><strong>It is applied to the horizontal axis only</strong> (see
     * {@link #xScaleFor}), and an animated drop takes its <em>height</em> from the art as
     * authored. That makes the contract for drop art: <em>a drop is drawn
     * {@code 0.8 * render_scale} cells wide and as many cells tall as its model says</em>,
     * so a drop that should be round has to be authored square at exactly that width. The
     * coins are (see {@code animations/resource/coin_*.json}); before they were, setting
     * {@code render_scale} made them narrower without making them smaller, and the height
     * the player judged the size by never moved at all.
     *
     * <p>Capped at 3x so a badly scaled animation file cannot fill the lawn by accident.
     */
    private float visualScale(ClientEntity entity) {
        // Drops only. A plant's or a zombie's animation is already authored at the size the
        // creature is meant to be, and their entries in the visual table are about shadows
        // and sort order; scaling them by the same rule made every zombie 12% bigger.
        if (!EntityKind.RESOURCE.equals(entity.kind())) {
            return 1F;
        }
        float[] size = visualSize(entity);
        if (size == null) {
            return 1F;
        }
        float largest = Math.max(size[0], size[1]);
        if (largest <= 0.0001F) {
            return 1F;
        }
        float target = EntityVisuals.of(entity.kind()).spriteWidth();
        return Math.max(0.05F, Math.min(3F, target / largest));
    }

    void onPlaybackStopped(AnimationPlayback playback) {
        // Removal is handled by stop()/tick(); this hook exists so handles can
        // report inactive even before the next manager tick.
    }

    void fireSound(AnimationCue.Sound cue) {
        if (client != null && client.sound() != null && cue.effect() != null) {
            client.sound().play(cue.effect().toString(), cue.volume(), cue.pitch());
        }
    }

    void fireParticle(AnimationCue.Particle cue, AnimationPlayback playback) {
        if (client == null || cue.effect() == null) {
            return;
        }
        float[] position = playback.eventPosition(cue.locator());
        client.particles().spawn(cue.effect().toString(), position[0], position[1]);
    }

    float clientSpriteXScale() {
        return client == null ? 1F : client.spriteXScale();
    }

    public Optional<AnimationFile> file(Identifier id) {
        return load(id);
    }

    /**
     * A single texture that stands for this content when a full render is not wanted.
     *
     * <p>Used by list rows and palette entries. The old sprites under
     * {@code textures/entities/<id>.png} are the pre-controller art and look nothing like
     * what the game draws, so a list built from them showed art the player never sees.
     * This picks the largest part of a controller model (the body, usually) or the first
     * flipbook frame, which is the art the game actually uses.
     *
     * @return the texture id, or empty when this content has no animation resource
     */
    public Optional<Identifier> iconTexture(Identifier id) {
        if (id == null) {
            return Optional.empty();
        }
        Optional<AnimationFile> file = load(id);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        if (file.get() instanceof ControllerFile controller) {
            ControllerModel.Part best = null;
            float bestArea = -1F;
            for (ControllerModel.Bone bone : controller.model().renderOrder()) {
                if (bone.restPose() != null && !bone.restPose().visible()) {
                    continue;
                }
                for (ControllerModel.Part part : bone.parts()) {
                    if (part.texture() == null) {
                        continue;
                    }
                    float area = Math.abs(part.sizeX() * part.sizeY());
                    if (area > bestArea) {
                        bestArea = area;
                        best = part;
                    }
                }
            }
            return best == null ? Optional.empty() : Optional.of(best.texture());
        }
        if (file.get() instanceof FlipbookFile flipbook) {
            // clip() is typed to the AnimationClip interface, so narrow before reading frames.
            AnimationClip idle = flipbook.clip("idle").orElse(null);
            FlipbookClip chosen = idle instanceof FlipbookClip f ? f
                    : flipbook.clips().values().stream().findFirst().orElse(null);
            if (chosen != null && !chosen.frames().isEmpty()) {
                return Optional.of(chosen.frames().get(0));
            }
        }
        return Optional.empty();
    }

    /**
     * Visual reference size in world cells for the entity's current
     * animation, or {@code null} when the entity has no animation resource.
     */
    public float[] visualSize(ClientEntity entity) {
        if (entity == null) {
            return null;
        }
        Optional<AnimationFile> file = fileFor(entity, entity.animation());
        if (file.isEmpty()) {
            return null;
        }
        AnimationFile animationFile = file.get();
        if (animationFile instanceof FlipbookFile flipbook) {
            return new float[]{flipbook.sizeX(), flipbook.sizeY()};
        }
        if (animationFile instanceof ControllerFile controller) {
            float width = controller.model().sizeX();
            float height = controller.model().sizeY();
            if (width > 0F && height > 0F) {
                return new float[]{width, height};
            }
        }
        return null;
    }

    private Optional<AnimationFile> fileFor(Animatable target, String state) {
        if (!(target instanceof ClientEntity entity)) {
            return Optional.empty();
        }
        Identifier defId = entity.defId();
        if (defId == null) {
            return Optional.empty();
        }
        AnimationBindings bindings = bindings(entity.kind(), defId);
        // An override names a file directly; otherwise the entity's own file lives in
        // the directory its definition declares (or mirrors the id when it declares none).
        Identifier fileId = bindings.resolve(state)
                .orElseGet(() -> bindings.fileId(defId));
        return load(fileId);
    }

    /**
     * Per-kind animation overrides, keyed by the registry that holds the definition.
     *
     * <p>The per-kind switch used to be written out three times in this class and
     * twice more in the screen layer, and had already drifted: {@code anchor} and
     * {@code baseZ} handled {@code "sun"} while {@code bindings} did not, so a
     * resource drop could never use an animation override. The lookup now lives in
     * {@link EntityArt}, which is also what the renderer asks for fallback sprites -
     * so "which art does this id use" has one answer again.
     */
    private AnimationBindings bindings(String kind, Identifier defId) {
        AnimationBindings bindings = EntityArt.bindings(defId);
        return bindings == null ? AnimationBindings.EMPTY : bindings;
    }

    /**
     * Loads and caches one animation file.
     *
     * <p>Only a genuinely absent file is remembered as "missing"; a parse failure is
     * remembered separately and reported with its cause. Both caches are dropped by
     * {@link #invalidate()} on a resource reload - previously a single malformed
     * keyframe marked the whole file missing for the rest of the session and no
     * reload or edit could bring it back, so one bad value silently removed an
     * entity's animation permanently.
     */
    private Optional<AnimationFile> load(Identifier id) {
        AnimationFile cached = files.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (missing.contains(id) || broken.contains(id)) {
            return Optional.empty();
        }
        String path = "assets/" + id.namespace() + "/animations/" + id.path() + ".json";
        try {
            var resource = resources.getResource(path);
            if (resource.isEmpty()) {
                missing.add(id);
                return Optional.empty();
            }
            JsonObject json = JsonParser.parseString(resource.get().readString()).getAsJsonObject();
            AnimationFile parsed = AnimationResourceLoader.parse(json, id);
            files.put(id, parsed);
            return Optional.of(parsed);
        } catch (Exception e) {
            broken.add(id);
            LOGGER.warn("Animation resource {} is present but could not be parsed; it will be retried after a "
                    + "resource reload: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    /** Drops every cached file so the next lookup re-reads the packs. */
    public void invalidate() {
        files.clear();
        missing.clear();
        broken.clear();
    }

    /** Ids whose file exists but failed to parse; used by diagnostics. */
    public java.util.Set<Identifier> brokenResources() {
        return java.util.Set.copyOf(broken);
    }

    private AnimationHandle handle(Animatable target, AnimationPlayback playback) {
        return new AnimationHandle() {
            @Override
            public String animation() {
                return playback.activeName();
            }

            @Override
            public boolean isActive() {
                return playbacks.get(target) == playback && !playback.isStopped();
            }

            @Override
            public boolean isFinished() {
                return playback.isFinished();
            }

            @Override
            public float progress() {
                return playback.progress();
            }

            @Override
            public void restart() {
                playback.restart();
            }

            @Override
            public void stop() {
                AnimationManager.this.stop(target);
            }

            @Override
            public void setSpeed(float speed) {
                playback.setSpeed(speed);
            }
        };
    }
}
