package com.pvzce.client;

import com.pvzce.api.entity.Entity;
import com.pvzce.api.util.Identifier;
import com.pvzce.client.animation.AnimationComponent;
import com.pvzce.client.animation.AnimationHandle;
import com.pvzce.client.animation.AnimationManager;
import com.pvzce.client.api.Animatable;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.EntityUpdateS2C;

/**
 * Client-side half of an entity.
 *
 * <p>It is a {@link Entity}, so position, health, animation and height live in the
 * same fields the server writes into its own entities and both sides derive the
 * grid cell the same way. The only client-specific addition is the animation
 * component; the kind and layer arrive from the spawn packet because layer is a
 * server decision (a zombie's layer changes while it digs or flies).
 */
public final class ClientEntity extends Entity implements Animatable {
    private final String kind;
    private final int layer;
    /** Owning team id as sent by the server; drives per-team rendering and access checks. */
    private final String teamId;
    /**
     * Remaining armour, or {@link EntitySpawnS2C#NO_ARMOR}.
     *
     * <p>Kept on the entity because it is state rather than art: the renderer asks a
     * zombie how worn its cone is the same way it asks for its health.
     */
    private int armor;
    private final AnimationComponent animationComponent = new AnimationComponent(this);

    public ClientEntity(int id, String kind, String defId, float cellX, float cellY, int health,
                        int layer, String animation, float height, String teamId) {
        this(id, kind, defId, cellX, cellY, health, layer, animation, height, teamId,
                EntitySpawnS2C.NO_ARMOR);
    }

    public ClientEntity(int id, String kind, String defId, float cellX, float cellY, int health,
                        int layer, String animation, float height, String teamId, int armor) {
        super(id, Identifier.tryParse(defId), cellX, cellY, health);
        this.kind = kind;
        this.layer = layer;
        this.teamId = teamId == null ? "" : teamId;
        this.armor = armor;
        setAnimation(animation);
        setHeight(height);
    }

    /** Rebuilds a client entity from a full-state spawn packet. */
    public static ClientEntity from(EntitySpawnS2C spawn) {
        return new ClientEntity(spawn.entityId(), spawn.entityKind(), spawn.defId(), spawn.cellX(),
                spawn.cellY(), spawn.health(), spawn.layer(), spawn.animation(), spawn.height(),
                spawn.teamId(), spawn.armor());
    }

    /** Remaining armour; {@link EntitySpawnS2C#NO_ARMOR} when this entity wears none. */
    public int armor() {
        return armor;
    }

    /** The team that owns this entity, or an empty string for team-less entities. */
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
    }

    /** Applies {@link EntityUpdateS2C} directly so the packet shape lives in one place. */
    public void apply(EntityUpdateS2C update) {
        update(update.cellX(), update.cellY(), update.health(), update.animation(), update.height(),
                update.armor());
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
