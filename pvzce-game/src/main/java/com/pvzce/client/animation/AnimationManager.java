package com.pvzce.client.animation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pvzce.api.content.AnimationBindings;
import com.pvzce.api.entity.EntityAnimations;
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
    /**
     * {@code file|state} pairs already reported as "this art has no such clip".
     *
     * <p>Set, not a counter: the render path asks for the same state every frame, and the
     * report exists so a content bug is findable in the log rather than so it is loud.
     */
    private final Set<String> substitutedClipReports = ConcurrentHashMap.newKeySet();
    private final Map<Animatable, AnimationPlayback> playbacks = new IdentityHashMap<>();
    /**
     * The bone override for the render call in progress, if any.
     *
     * <p>Set by {@link #render(ClientEntity)} for the duration of one entity and read by
     * {@link ControllerPlayback}. It is frame state rather than an argument because the
     * playback interface is shared with flipbooks and with callers that have no entity at
     * all; one entity is drawn at a time, so there is nothing to interleave.
     */
    private BoneArt activeBoneArt;

    public AnimationManager(PvzceClient client, ClientLevel level, PvzceResourceManager resources) {
        this.client = client;
        this.level = level;
        this.resources = resources;
    }

    /** Current world animation time in game seconds (60 game ticks = 1 second). */
    public double now() {
        return level == null ? 0D : level.gameSeconds();
    }

    /**
     * Anchor for world entities; UI targets can override later.
     *
     * <p>Reads the <em>drawn</em> position, not the server's. The mirror only publishes an
     * entity every third tick (20 Hz) while the client draws 60 to 260 frames, so anchoring
     * a skeleton to {@code cellX()} put the whole animated body on a 20 Hz staircase while
     * its own shadow - which uses the interpolated position - slid smoothly underneath it.
     * A walking zombie's feet visibly left its shadow twenty times a second, and a falling
     * drop stepped down the screen in five-frame jumps. {@code cellX()} and {@code height()}
     * remain the authoritative numbers everywhere else; this is a render position and only
     * a render position, exactly like the sprite fallback and the shadow.
     */
    public float[] anchor(Animatable target) {
        if (!(target instanceof ClientEntity entity)) {
            return new float[]{0F, 0F};
        }
        float lift = com.pvzce.client.renderer.EntityVisuals.anchorLift(entity.kind());
        return new float[]{entity.visualCellX(),
                entity.visualCellY() + entity.visualHeight() - lift};
    }

    public float baseZ(Animatable target) {
        if (!(target instanceof ClientEntity entity)) {
            return com.pvzce.client.renderer.EntityVisuals.baseZ("");
        }
        return com.pvzce.client.renderer.EntityVisuals.baseZ(entity.kind());
    }

    /**
     * Unified playback entry point. Repeated states are idempotent.
     *
     * <p>A state is not always a clip: the death states are a family the art chooses from
     * (see {@link AnimationVariants}), so the clip that ends up playing is what
     * "is this already playing" has to be asked about.
     */
    public AnimationHandle play(Animatable target, String state) {
        if (target == null || state == null || state.isBlank()) {
            return AnimationHandle.NONE;
        }
        Identifier fileId = fileIdFor(target, state);
        AnimationFile animationFile = fileId == null ? null : load(fileId).orElse(null);
        if (animationFile == null) {
            stop(target);
            return AnimationHandle.NONE;
        }
        // Which clip this state actually plays: a member of the state's family, the state
        // itself, or - for a state the art does not define - `idle`, which is the same
        // substitution every unknown clip has always got.
        String resolved = AnimationVariants.resolve(animationFile, state, variantSeed(target));
        String activeName = resolved == null ? EntityAnimations.IDLE : resolved;
        AnimationClip clip = animationFile.clip(activeName).orElse(null);
        if (clip == null) {
            LOGGER.warn("Animation {} has no '{}' or 'idle' clip for {}", animationFile.type(), state, target);
            stop(target);
            return AnimationHandle.NONE;
        }
        if (resolved == null) {
            // The substitution is silent, but it is also a content bug, and it used to leave
            // no trace at all: a newspaper zombie whose file has no `death2` stood about in
            // `idle` for six seconds with nothing in the log to say why.
            reportSubstitutedClip(fileId, state, target);
        }
        AnimationPlayback current = playbacks.get(target);
        if (current != null && !current.isStopped()
                && isAlreadyPlaying(current, state, activeName)) {
            return handle(target, current);
        }

        AnimationPlayback previous = current != null && !current.isStopped() ? current : null;
        double now = now();
        AnimationPlayback playback = create(target, animationFile, clip, state, activeName, previous, now);
        playbacks.put(target, playback);
        return handle(target, playback);
    }

    /**
     * A stable number per target, for choosing among a state's family of clips.
     *
     * <p>The entity's id, not a random roll: the id is allocated by the server in level
     * order, so the same run of the same level deals out the same death sequences to the
     * same bodies - which is what the server's own {@code level.random()} pick used to buy,
     * and what a replay of a recorded level still needs.
     */
    private static long variantSeed(Animatable target) {
        return target instanceof ClientEntity entity ? entity.id() : 0L;
    }

    /**
     * Says once per (file, state) that a requested state was substituted.
     *
     * <p>Deduplicated because this is asked every frame by the render path; the point of
     * the report is that a content bug becomes findable, not that it becomes loud.
     */
    private void reportSubstitutedClip(Identifier fileId, String state, Animatable target) {
        if (substitutedClipReports.add(fileId + "|" + state)) {
            LOGGER.warn("Animation {} has no '{}' clip for {}; playing 'idle' instead",
                    fileId, state, target);
        }
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
     *
     * <p>{@code activeName} is the <em>resolved</em> clip rather than the state: a death
     * request resolves to one member of its family, and comparing the state against that
     * member would read as "not playing yet" on every frame and restart the corpse twenty
     * times a second.
     */
    private static boolean isAlreadyPlaying(AnimationPlayback current, String state, String activeName) {
        if (!state.equals(current.requestedState())) {
            return false;
        }
        if (!activeName.equals(current.activeName())) {
            // It handed over to another clip (to idle, or to its `next`), so this state is
            // no longer on screen and asking for it again has to start it.
            return false;
        }
        if (current.clip().loop()) {
            return true;
        }
        // A one-shot that ran out and is *holding* its last frame is still this state's
        // playback. The server republishes an entity's state every few ticks, so treating
        // "finished" as "start it again" restarted a held clip on the next update: a
        // zombie's death animation played two or three times over the corpse's six
        // seconds. Only a clip that never holds - or one that has not ended yet - counts
        // as something a re-request may restart.
        return !current.isFinished()
                || current.clip().onEnd() == AnimationClip.OnEnd.HOLD;
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
            playback.measureLocomotion(now);
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
        // Two per-frame facts the clip cannot know, both from the entity's synced state:
        //
        //   * a charmed zombie walks the other way, so its art is mirrored - otherwise it
        //     strides backwards up the lane, facing the house it is leaving;
        //   * a frozen zombie holds the pose it was in. The server already stops moving it;
        //     this stops the walk cycle playing on the spot, which is the difference between
        //     "frozen solid" and "walking without going anywhere".
        playback.setFlipX(EntityKind.ZOMBIE.equals(entity.kind()) && entity.charmed());
        // Dying is not walking: a frozen zombie that is killed plays its death, or the corpse
        // would stand there until the freeze ran out.
        playback.setPaused(EntityKind.ZOMBIE.equals(entity.kind()) && entity.frozen()
                && entity.health() > 0);
        float[] anchor = anchor(entity);
        float[] scales = scalesFor(entity);
        // How this entity is lit: a drop wears its resource's own tint - dimmer than its art, so
        // its glow layers do not clip to white, and as warm as the resource says, so a sun stays
        // yellow - while everything else wears the night lift and, if it is a slowed zombie, the
        // frozen tint. One push, one pop, both paths below.
        if (EntityKind.RESOURCE.equals(entity.kind())) {
            float[] dropTint = EntityVisuals.dropTint(entity.defIdString());
            client.pushEntityTint(dropTint[0], dropTint[1], dropTint[2]);
        } else {
            client.pushEntityLook(entity);
        }
        activeBoneArt = playback.file() instanceof ControllerFile controller
                ? com.pvzce.client.renderer.EquipmentArt.forEntity(entity, controller.model())
                : null;
        try {
            playback.render(client, anchor[0], anchor[1], baseZ(entity), scales[0], scales[1]);
        } finally {
            activeBoneArt = null;
            client.popEntityTint();
        }
        return true;
    }

    /** The bone override of the render call in progress, or {@code null}. */
    BoneArt activeBoneArt() {
        return activeBoneArt;
    }

    /**
     * The two factors this entity's animation geometry is drawn with.
     *
     * <p>{@code [0]} is horizontal and {@code [1]} is vertical, and they differ only by the
     * board's own aspect correction: the projection maps a world cell to
     * {@code unitY / unitX} screen pixels (1.25, the original's 80x100 cell), and plant,
     * zombie and tool art is authored to be widened by it. So a sprite keeps its shape, and
     * content size is one number applied to both axes - {@link EntityArt#renderScale} times
     * the entity's own per-drop multiplier.
     *
     * <p>A drop used to be drawn without the board factor <em>and</em> with its art
     * auto-fitted to {@code 0.8} cells on the width alone. That pair of exceptions is where
     * every drop-sizing bug came from: the fit read the model's largest dimension, so a glow
     * authored wider than the sprite it belongs to decided how big the sprite was - the coin's
     * face ended up drawn at 54% of its own art - and the width-only application squashed
     * whatever it produced. A drop is now sized exactly like everything else: what the art
     * says, times {@code render_scale}.
     */
    private float[] scalesFor(ClientEntity entity) {
        boolean drop = EntityKind.RESOURCE.equals(entity.kind());
        float board = drop ? 1F : client.spriteXScale();
        float size = renderScale(entity);
        // Two factors, and each belongs to exactly one axis:
        //
        //   * `size` is the content's own size and goes on *both*, or the shape changes with it
        //     - which is how a sun with `render_scale: 2.2` arrived 2.2 times wider than it was
        //     tall, a flat disc. This is the whole contract of render_scale: two axes, one
        //     number, shape preserved;
        //   * `board` (the 80x100 cell correction) goes on the horizontal axis alone, because
        //     that is what it corrects. A drop skips it: its art is authored in cells and the
        //     renderer draws cells.
        return new float[]{board * size, size};
    }

    /** The definition's {@code render_scale}, or 1 for anything that declares none. */
    float renderScale(ClientEntity entity) {
        return EntityArt.renderScale(entity.defId()) * entity.renderScale();
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
        Identifier fileId = fileIdFor(target, state);
        return fileId == null ? Optional.empty() : load(fileId);
    }

    /**
     * The animation resource a state resolves to, or {@code null} for a target with none.
     *
     * <p>Separate from {@link #load} so a caller that has to name the resource - the
     * "this art has no such clip" report is keyed by it - does not have to derive the path
     * a second time.
     */
    private Identifier fileIdFor(Animatable target, String state) {
        if (target instanceof ArtTarget art) {
            // Not content, so there is no definition to ask: the target names its own file.
            // See ArtTarget - a level mechanic's prop has no registry entry to resolve.
            return art.fileId();
        }
        if (!(target instanceof ClientEntity entity)) {
            return null;
        }
        Identifier defId = entity.defId();
        if (defId == null) {
            return null;
        }
        AnimationBindings bindings = bindings(entity.kind(), defId);
        // An override names a file directly; otherwise the entity's own file lives in
        // the directory its definition declares (or mirrors the id when it declares none).
        return bindings.resolve(state)
                .orElseGet(() -> bindings.fileId(defId));
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
     *
     * <p>The kind travels with the id because an id can be in two registries at once - the
     * snow pea is a plant and a projectile - and the answer has to be the one for the thing
     * being drawn. It used to be dropped here, and every snow pea in flight was drawn with
     * the plant's controller model.
     */
    private AnimationBindings bindings(String kind, Identifier defId) {
        AnimationBindings bindings = EntityArt.bindings(defId, kind);
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
        // Damage-state bone families are resolved against a parsed model, so a reloaded
        // pack must not keep being drawn from the plan built for the old one.
        com.pvzce.client.renderer.EquipmentArt.clearCache();
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
