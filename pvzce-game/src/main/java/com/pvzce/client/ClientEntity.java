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
    private final AnimationComponent animationComponent = new AnimationComponent(this);

    public ClientEntity(int id, String kind, String defId, float cellX, float cellY, int health,
                        int layer, String animation, float height, String teamId) {
        super(id, Identifier.tryParse(defId), cellX, cellY, health);
        this.kind = kind;
        this.layer = layer;
        this.teamId = teamId == null ? "" : teamId;
        setAnimation(animation);
        setHeight(height);
    }

    /** Rebuilds a client entity from a full-state spawn packet. */
    public static ClientEntity from(EntitySpawnS2C spawn) {
        return new ClientEntity(spawn.entityId(), spawn.entityKind(), spawn.defId(), spawn.cellX(),
                spawn.cellY(), spawn.health(), spawn.layer(), spawn.animation(), spawn.height(),
                spawn.teamId());
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
        setCellX(cellX);
        setCellY(cellY);
        setHealth(health);
        setAnimation(animation);
        setHeight(height);
    }

    /** Applies {@link EntityUpdateS2C} directly so the packet shape lives in one place. */
    public void apply(EntityUpdateS2C update) {
        update(update.cellX(), update.cellY(), update.health(), update.animation(), update.height());
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
