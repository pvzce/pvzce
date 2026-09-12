package com.pvzce.server.entity;

import com.pvzce.api.entity.Entity;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.packet.EntitySpawnS2C;
import com.pvzce.common.network.packet.EntityUpdateS2C;
import com.pvzce.server.Team;
import com.pvzce.server.level.LevelServer;

/**
 * Server-authoritative entity base (plants, zombies, projectiles, sun drops).
 *
 * <p>All state that the client also needs lives in {@link Entity}; this class
 * only adds what is server-only: the owning team, the removal flag, the tick
 * entry point and symmetric save/restore.
 */
public abstract class PvzceEntity extends Entity {
    public static final int LAYER_UNDERGROUND = EntityLayers.UNDERGROUND;
    public static final int LAYER_GROUND = EntityLayers.GROUND;
    public static final int LAYER_PLANT = EntityLayers.PLANT;
    public static final int LAYER_PROJECTILE = EntityLayers.PROJECTILE;
    public static final int LAYER_AIR = EntityLayers.AIR;

    protected final Team team;
    protected boolean removed;

    protected PvzceEntity(Identifier defId, Team team, float cellX, float cellY, int health) {
        super(defId, cellX, cellY, health);
        this.team = team;
    }

    public abstract void tick(LevelServer level);

    public Team team() {
        return team;
    }

    public boolean isRemoved() {
        return removed;
    }

    public void remove() {
        removed = true;
    }

    @Override
    public void damage(int amount) {
        setHealth(Math.max(0, health() - amount));
        if (health() <= 0) {
            removed = true;
        }
    }

    public EntitySpawnS2C spawnPacket() {
        return new EntitySpawnS2C(id(), entityKind(), defId().toString(),
                team == null ? "" : team.id().toString(),
                cellX(), cellY(), layer(), health(), animation(), height());
    }

    public EntityUpdateS2C updatePacket() {
        return new EntityUpdateS2C(id(), cellX(), cellY(), health(), animation(), height());
    }

    /**
     * Per-entity state that is not part of its content definition. Every entity
     * implements this so save/restore is symmetric by construction - previously
     * zombies round-tripped fully while plants silently lost their timers,
     * stacking height and sub-cell position.
     */
    public abstract CompoundTag saveState();

    /** Restores a tag produced by {@link #saveState()} on a freshly created entity. */
    public abstract void restoreState(CompoundTag tag);

    /** Writes the fields every entity shares; subclasses call this first. */
    protected CompoundTag saveBaseState() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", defId().toString());
        tag.putFloat("x", cellX());
        tag.putFloat("y", cellY());
        tag.putFloat("height", height());
        tag.putInt("health", health());
        tag.putString("animation", animation());
        return tag;
    }

    /** Reads the fields written by {@link #saveBaseState()}. */
    protected void restoreBaseState(CompoundTag tag) {
        setCellX(tag.getFloat("x"));
        setCellY(tag.getFloat("y"));
        setHeight(tag.getFloat("height"));
        setHealth(tag.getInt("health"));
        setAnimation(tag.getString("animation"));
    }

    /**
     * Reads everything {@link #saveBaseState()} wrote <em>except</em> the position.
     *
     * <p>For the glove: a moved plant is re-spawned at its new cell and then has its state
     * put back, and restoring {@code x}/{@code y} as well would move it straight back to
     * where it came from - which is exactly what happened, with the id copied as well so
     * the cell it landed in looked empty.
     *
     * <p>Height is left alone too: it belongs to the new cell's terrain and the plant's
     * new place in its stack, both of which {@code spawnPlant} has already worked out.
     */
    protected void restoreStateWithoutPosition(CompoundTag tag) {
        setHealth(tag.getInt("health"));
        setAnimation(tag.getString("animation"));
    }
}
