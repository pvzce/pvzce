package com.pvzce.client;

import com.pvzce.common.level.WorldPosition;
import com.pvzce.api.entity.Entity;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.animation.AnimationComponent;
import com.pvzce.client.animation.AnimationHandle;
import com.pvzce.client.animation.AnimationManager;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.EntityUpdateS2C;
import com.pvzce.common.network.packet.EchoNetworkS2C;

/**
 * Client-side half of an entity.
 *
 * <p>It is a {@link Entity}, so position, health, animation and height live in the
 * same fields the server writes into its own entities and both sides derive the
 * grid cell the same way. The only client-specific addition is the animation
 * component; the kind and layer arrive from the spawn packet because layer is a
 * server decision (a zombie's layer changes while it digs or flies).
 */
public final class ClientEntity extends Entity implements com.pvzce.client.api.MovingTarget {
    private EchoNetworkS2C echoNetwork;
    private boolean fertilized;
    private boolean laddered;
    public boolean fertilized() { return fertilized; }
    public boolean laddered() { return laddered; }
    public void apply(com.pvzce.common.network.packet.PlantCareS2C state) {
        fertilized = state.fertilized();
        laddered = state.laddered();
    }

    public EchoNetworkS2C echoNetwork() {
        return echoNetwork == null ? EchoNetworkS2C.empty(id()) : echoNetwork;
    }

    public void apply(EchoNetworkS2C status) {
        echoNetwork = status;
    }

    private final String kind;
    private final int layer;
    /** Owning team id as sent by the server; drives per-team rendering and access checks. */
    /**
     * The team that owns this entity, or an empty string for team-less entities.
     *
     * <p>Not final because a zombie's side can change under it: a charmed zombie is moved to
     * the plants' team, and the visual that says so is driven from here (see
     * {@link #charmed()}).
     */
    private String teamId;
    /**
     * Remaining armour, or {@link EntitySpawnS2C#NO_ARMOR}.
     *
     * <p>Kept on the entity because it is state rather than art: the renderer asks a
     * zombie how worn its cone is the same way it asks for its health.
     */
    private int armor;
    /**
     * Whether the server currently has this zombie under a {@code slow} status.
     *
     * <p>State, like the armour above it, and for the same reason: the client draws the
     * frozen look from it and cannot work it out from anything else it is sent. Always
     * false for anything that is not a zombie.
     */
    private boolean chilled;
    /**
     * Whether the server has this zombie fighting for the side it was spawned against.
     *
     * <p>State, like the armour and the chill above it: the client draws the charmed look from
     * it and cannot work it out from anything else it is sent, because "which side is this" is
     * not something a position or an animation says. Always false for anything that is not a
     * zombie.
     */
    private boolean charmed;
    /**
     * Whether the server has this zombie held solid (the ice-shroom's freeze).
     *
     * <p>State, like the two above it. The client needs it for two things the server cannot do
     * from its side: it draws the ice under the zombie, and it stops the clip - a frozen zombie
     * that kept walking its walk cycle on the spot would read as "stuck", not as "frozen".
     * Always false for anything that is not a zombie.
     */
    private boolean frozen;
    private boolean buttered;
    private int lastAnimationSequence;

    public boolean buttered() { return buttered; }
    /**
     * Draw-size multiplier on top of the definition's own {@code render_scale}.
     *
     * <p>{@link EntitySpawnS2C#DEFAULT_SCALE} for everything whose size is content; a
     * small sun-shroom's sun is the one shipped drop that is the same resource at a
     * smaller size (see {@code EntitySpawnS2C#scale}).
     */
    private final float renderScale;
    private final AnimationComponent animationComponent = new AnimationComponent(this);

    public ClientEntity(int id, String kind, String defId, float cellX, float cellY, int health,
                        int layer, String animation, float height, String teamId) {
        this(id, kind, defId, cellX, cellY, health, layer, animation, height, teamId,
                EntitySpawnS2C.NO_ARMOR, false, EntitySpawnS2C.DEFAULT_SCALE);
    }

    public ClientEntity(int id, String kind, String defId, float cellX, float cellY, int health,
                        int layer, String animation, float height, String teamId, int armor) {
        this(id, kind, defId, cellX, cellY, health, layer, animation, height, teamId, armor,
                false, EntitySpawnS2C.DEFAULT_SCALE);
    }

    public ClientEntity(int id, String kind, String defId, float cellX, float cellY, int health,
                        int layer, String animation, float height, String teamId, int armor,
                        boolean chilled, float renderScale) {
        this(id, kind, defId, cellX, cellY, health, layer, animation, height, teamId, armor,
                chilled, false, renderScale);
    }

    public ClientEntity(int id, String kind, String defId, float cellX, float cellY, int health,
                        int layer, String animation, float height, String teamId, int armor,
                        boolean chilled, boolean charmed, float renderScale) {
        super(id, Identifier.tryParse(defId), cellX, cellY, health);
        this.kind = kind;
        this.layer = layer;
        this.teamId = teamId == null ? "" : teamId;
        this.armor = armor;
        this.chilled = chilled;
        this.charmed = charmed;
        this.frozen = frozen;
        this.renderScale = renderScale <= 0F ? EntitySpawnS2C.DEFAULT_SCALE : renderScale;
        setAnimation(animation);
        setHeight(height);
    }

    /** Rebuilds a client entity from a full-state spawn packet. */
    public static ClientEntity from(EntitySpawnS2C spawn) {
        ClientEntity entity = new ClientEntity(spawn.entityId(), spawn.entityKind(), spawn.defId(),
                spawn.cellX(), spawn.cellY(), spawn.health(), spawn.layer(), spawn.animation(),
                spawn.height(), spawn.teamId(), spawn.armor(), spawn.chilled(), spawn.scale());
        // What it spawned with, which is not the same as what it has: the base class records the
        // spawn health as the ceiling, and the packet carries the real one (wave growth x the
        // world's difficulty tier). A health bar drawn against the wrong ceiling shows a
        // hell-tier buckethead as permanently full.
        entity.setMaxHealth(spawn.maxHealth());
        entity.setSurfaceId(spawn.surfaceId());
        return entity;
    }

    /** Remaining armour; {@link EntitySpawnS2C#NO_ARMOR} when this entity wears none. */
    public int armor() {
        return armor;
    }

    /** True while the server has this zombie slowed, which is drawn as the frozen look. */
    public boolean chilled() {
        return chilled;
    }

    /**
     * True when this zombie is fighting for the side it was spawned against.
     *
     * <p>The visual is a tint, so a charmed zombie is legible in a lane full of ordinary ones
     * without a second sprite set - which is also what the original does (the zombie turns
     * purple and keeps its own art).
     */
    public boolean charmed() {
        return charmed;
    }

    /** True while the server has this zombie held solid; see {@link #frozen}. */
    public boolean frozen() {
        return frozen;
    }


    /**
     * The per-entity draw-size multiplier.
     *
     * <p>Multiplied with {@code EntityArt.renderScale} (the definition's own number) by
     * whoever draws the entity; one alone is never the whole size.
     */
    public float renderScale() {
        return renderScale;
    }

    /**
     * The team that owns this entity, or an empty string for team-less entities.
     *
     * <p>A zombie's can change while it is on the board: a charmed one is moved to the plants'
     * team by the server, and the update that says so is applied here. Nothing on the client
     * simulates sides, so this is only ever read for what to draw.
     */
    public String teamId() {
        return teamId;
    }

    public String kind() {
        return kind;
    }

    /** Definition id as a string; kept for the rendering layer which keys on text. */
    public String defIdString() {
        return defId() == null ? "" : defId().toString();
    }

    @Override
    public String entityKind() {
        return kind;
    }

    @Override
    public int layer() {
        return layer;
    }

    /**
     * How long one server sync period is, in nanoseconds.
     *
     * <p>{@code LevelServer.syncSlots} publishes every entity's position every
     * {@code tickCount % 3 == 0} - 20 times a second, against a client that draws 60 to 260
     * times. Drawing the raw packet position is therefore a 20 Hz staircase: the zombie
     * stands still for five frames and then jumps, which reads as a stutter and is worst
     * exactly when the player is watching one zombie (<em>being hit</em>, being eaten,
     * walking the last cell).
     *
     * <p>The two numbers are coupled by construction, so they are stated here together:
     * if the server's cadence changes, this is the constant that has to follow it. Getting
     * it wrong is graceful in both directions - too long only makes the slide lag behind
     * the packet, too short reaches the sample early and then waits.
     */
    public static final long SYNC_PERIOD_NANOS =
            3L * 1_000_000_000L / com.pvzce.common.PvzceConstants.TICKS_PER_SECOND;

    /**
     * Where the last packet left this entity, and when it arrived.
     *
     * <p>Only <em>rendering</em> samples: {@link #cellX()} and {@link #height()} stay the
     * server's numbers, so hit tests, camera maths and anything a test asserts keep reading
     * the authoritative value. Height is in here because it is not decoration: a falling
     * drop's height is what carries it down the screen, so interpolating x and y while
     * stepping the height would still leave the sun's fall at 20 Hz.
     */
    private float renderCellX;
    private float renderCellY;
    private float renderHeight;
    private long syncNanos;
    /** False until an update lands, so a fresh spawn is never interpolated from nowhere. */
    private boolean interpolating;

    /**
     * The x to draw this entity at, sliding from the previous sample to the current one.
     *
     * <p>Linear on the wall clock rather than eased: the entity is moving at a constant
     * speed between two samples, and an ease would make it visibly accelerate and brake
     * twenty times a second. The result is clamped to the segment, so a late packet can
     * never overshoot past the position the server has already given.
     */
    public float visualCellX() {
        return slide(renderCellX, cellX());
    }

    /** {@inheritDoc} The pair {@link com.pvzce.client.api.MovingTarget} asks a playback for. */
    @Override
    public float drawnX() {
        return visualCellX();
    }

    /** {@inheritDoc} */
    @Override
    public float drawnY() {
        return visualCellY();
    }

    /** The y to draw this entity at; same rule as {@link #visualCellX()}. */
    public float visualCellY() {
        return slide(renderCellY, cellY());
    }

    /** How high off its cell this entity is drawn; same rule as {@link #visualCellX()}. */
    public float visualHeight() {
        return slide(renderHeight, height());
    }

    private float slide(float from, float to) {
        if (!interpolating || from == to) {
            return to;
        }
        return from + (to - from) * syncProgress();
    }

    /**
     * 0..1 through the current sync period, clamped.
     *
     * <p>Computed once per read rather than per frame: it is a clock value, and the three
     * positions an entity has must slide together or a falling drop would bend.
     */
    private float syncProgress() {
        return Math.min(1F, Math.max(0F,
                (System.nanoTime() - syncNanos) / (float) SYNC_PERIOD_NANOS));
    }

    /** Applies a delta update; only the fields the server streams are touched. */
    public void update(float cellX, float cellY, int health, String animation, float height) {
        update(cellX, cellY, health, animation, height, armor);
    }

    public void update(float cellX, float cellY, int health, String animation, float height, int armor) {
        update(cellX, cellY, health, animation, height, armor, chilled);
    }

    public void update(float cellX, float cellY, int health, String animation, float height, int armor,
                       boolean chilled) {
        update(cellX, cellY, health, animation, height, armor, chilled, false,
                EntityUpdateS2C.NO_TEAM);
    }

    public void update(float cellX, float cellY, int health, String animation, float height, int armor,
                       boolean chilled, boolean charmed, String teamId) {
        update(cellX, cellY, health, animation, height, armor, chilled, charmed, false, teamId);
    }

    public void update(float cellX, float cellY, int health, String animation, float height, int armor,
                       boolean chilled, boolean charmed, boolean frozen, String teamId) {
        update(cellX, cellY, health, animation, height, armor, chilled, charmed, frozen, false, teamId);
    }

    public void update(float cellX, float cellY, int health, String animation, float height, int armor,
                       boolean chilled, boolean charmed, boolean frozen, boolean buttered, String teamId) {
        // Interpolation starts from where this entity is being *drawn*, not from where the
        // last packet put it: a packet delayed past one sync period would otherwise make the
        // entity jump backwards to the previous sample before sliding forward again.
        this.renderCellX = visualCellX();
        this.renderCellY = visualCellY();
        this.renderHeight = visualHeight();
        this.syncNanos = System.nanoTime();
        this.interpolating = true;
        setCellX(cellX);
        setCellY(cellY);
        setHealth(health);
        setAnimation(animation);
        setHeight(height);
        this.armor = armor;
        this.chilled = chilled;
        this.charmed = charmed;
        this.frozen = frozen;
        this.buttered = buttered;
        // An empty id means "unchanged", so a level that has never charmed anything keeps
        // sending the same string it did from the spawn packet without anything resetting it.
        if (teamId != null && !teamId.isEmpty()) {
            this.teamId = teamId;
        }
        // Consume streamed state changes even between render frames.
        playAnimation(animation);
    }

    /** Applies {@link EntityUpdateS2C} directly so the packet shape lives in one place. */
    public void apply(EntityUpdateS2C update) {
        setSurfaceId(update.surfaceId());
        if (lastAnimationSequence != update.animationSequence()) {
            stopAnimation();
            lastAnimationSequence = update.animationSequence();
        }
        update(update.cellX(), update.cellY(), update.health(), update.animation(), update.height(),
                update.armor(), update.chilled(), update.charmed(), update.frozen(), update.buttered(),
                update.teamId());
    }

    public WorldPosition visualPosition() {
        return new WorldPosition(visualCellX(), visualCellY(), visualHeight());
    }

    public void attachAnimationManager(AnimationManager manager) {
        animationComponent.attach(manager);
    }

    public AnimationComponent animationComponent() {
        return animationComponent;
    }

    @Override
    public AnimationHandle playAnimation(String animation) {
        return animationComponent.play(animation);
    }

    @Override
    public void stopAnimation() {
        animationComponent.stop();
    }

    @Override
    public String currentAnimation() {
        return animationComponent.requestedState();
    }
}
