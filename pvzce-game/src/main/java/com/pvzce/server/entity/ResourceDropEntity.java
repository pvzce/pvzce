package com.pvzce.server.entity;

import com.pvzce.api.content.ResourceDef;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.EntityKind;
import com.pvzce.api.entity.EntityLayers;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.Team;
import com.pvzce.server.level.LevelServer;

/**
 * A falling sun/resource drop; collectible only with the matching resource card.
 *
 * <p>The fall is driven by {@code height}, not by {@code cellY}: the drop's row
 * is fixed at the cell it was spawned in, so {@code gridY()} is correct from the
 * first tick. Previously {@code cellY} was animated, which made a drop spawned in
 * row 0 report row 2 while it fell.
 */
public class ResourceDropEntity extends PvzceEntity {
    /** Spawn above the cell and fall for ~2.5s so drops are visible almost immediately. */
    public static final float START_HEIGHT = 2.2F;
    public static final int FALL_DURATION_TICKS = 150;
    /** Drops disappear after this long on the ground (10s at 60tps). */
    public static final int LIFETIME_TICKS = 600;

    private final ResourceDef def;
    private final int amount;
    private final float fallSpeed = START_HEIGHT / FALL_DURATION_TICKS;
    private boolean landed;
    private int expireTicks;
    private boolean collected;

    public ResourceDropEntity(ResourceDef def, Team team, int gridX, int gridY, int amount) {
        super(def.id(), team, gridX + 0.5F, gridY + 0.5F, 1);
        this.def = def;
        this.amount = amount;
        setHeight(START_HEIGHT);
    }

    public ResourceDef def() {
        return def;
    }

    public int amount() {
        return amount;
    }

    public boolean collected() {
        return collected;
    }

    public boolean landed() {
        return landed;
    }

    public void markCollected() {
        collected = true;
        remove();
    }

    @Override
    public String entityKind() {
        return EntityKind.RESOURCE;
    }

    @Override
    public int layer() {
        return EntityLayers.AIR;
    }

    @Override
    public void tick(LevelServer level) {
        if (removed) {
            return;
        }
        if (!landed) {
            setHeight(height() - fallSpeed);
            if (height() <= 0F) {
                setHeight(0F);
                landed = true;
                setAnimation(EntityAnimations.LANDED);
            }
        } else if (++expireTicks >= LIFETIME_TICKS) {
            remove();
        }
    }

    @Override
    public CompoundTag saveState() {
        CompoundTag tag = saveBaseState();
        tag.putInt("amount", amount);
        tag.putInt("landed", landed ? 1 : 0);
        tag.putInt("expireTicks", expireTicks);
        tag.putInt("collected", collected ? 1 : 0);
        return tag;
    }

    @Override
    public void restoreState(CompoundTag tag) {
        restoreBaseState(tag);
        landed = tag.getInt("landed") != 0;
        expireTicks = tag.getInt("expireTicks");
        collected = tag.getInt("collected") != 0;
    }
}
