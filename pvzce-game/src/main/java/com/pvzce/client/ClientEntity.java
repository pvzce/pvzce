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

    /** Applies a delta update; only the fields the server streams are touched. */
    public void update(float cellX, float cellY, int health, String animation, float height) {
        update(cellX, cellY, health, animation, height, armor);
    }

    public void update(float cellX, float cellY, int health, String animation, float height, int armor) {
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
