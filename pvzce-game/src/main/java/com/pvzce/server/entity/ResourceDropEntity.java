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
    /** Height a sky drop starts from; it falls to the ground over {@link #FALL_TICKS}. */
    public static final float START_HEIGHT = 2.2F;
    /**
     * How long a sky drop takes to reach the ground.
     *
     * <p>Doubled from the original 150 ticks: at the old speed the sun was past the grass
     * before the player had looked at it.
     */
    public static final int FALL_TICKS = 300;
    /** Drops disappear after this long on the ground (10s at 60tps). */
    public static final int LIFETIME_TICKS = 600;

    private final ResourceDef def;
    private final int amount;
    private final float fallSpeed = START_HEIGHT / FALL_TICKS;
    private boolean landed;
    private float groundHeight;
    private boolean surfaceInitialized;
    private int expireTicks;
    private boolean collected;
    /** Ticks spent rising, for {@link ResourceDef.DropMotion#RISE}. */
    private int riseTicks;
    /** Where a {@link ResourceDef.DropMotion#RISE} arc started, before its sideways throw. */
    private float riseOriginX;
    /** How far sideways this drop was thrown, in cells; decided once, at construction. */
    private float driftX;

    /** The reason a drop's motion can be overridden: see {@link #motionOverride}. */
    private final ResourceDef.DropMotion motionOverride;

    /**
     * How much bigger than the resource's own art this particular drop is drawn.
     *
     * <p>One resource, two sizes: a sun is worth 25 and a small sun-shroom's sun is worth
     * 15, and the original draws the 15 one smaller. The size belongs to the drop rather
     * than to the resource because only the producer knows which of the two it just made.
     *
     * <p>Not final, because it is state: a drop saved mid-fall and restored has to come
     * back the size it was, or a small sun grows into a big one by being reloaded.
     */
    private float renderScale;

    public ResourceDropEntity(ResourceDef def, Team team, int gridX, int gridY, int amount) {
        this(def, team, gridX, gridY, amount, null, 0F);
    }

    /**
     * @param motion overrides the resource's own {@code drop_motion}, or {@code null} to
     *               use it. A sunflower's sun is the case: the resource is "a sun", but
     *               this particular one came out of a flower.
     * @param driftX how far sideways this drop sits from its cell's centre, in cells. Rolled by
     *               the caller from the level's own {@code Random} - entities never allocate
     *               one - so a drop keeps the throw it was given, including across a save.
     *               A {@code RISE} arc travels it while it floats up; a {@code FALL} drop simply
     *               starts there, which is how one pot's bundle of suns lands side by side
     *               instead of stacked on one point.
     */
    public ResourceDropEntity(ResourceDef def, Team team, int gridX, int gridY, int amount,
                              ResourceDef.DropMotion motion, float driftX) {
        this(def, team, gridX, gridY, amount, motion, driftX,
                com.pvzce.common.network.packet.EntitySpawnS2C.DEFAULT_SCALE);
    }

    /** The full constructor, with the per-drop draw scale. */
    public ResourceDropEntity(ResourceDef def, Team team, int gridX, int gridY, int amount,
                              ResourceDef.DropMotion motion, float driftX, float renderScale) {
        super(def.id(), team, gridX + 0.5F, gridY + 0.5F, 1);
        this.def = def;
        this.amount = amount;
        this.motionOverride = motion;
        this.riseOriginX = cellX();
        this.driftX = driftX;
        this.renderScale = renderScale <= 0F
                ? com.pvzce.common.network.packet.EntitySpawnS2C.DEFAULT_SCALE
                : renderScale;
        switch (motion()) {
            case FALL -> {
                setHeight(START_HEIGHT);
                setCellX(cellX() + driftX);
            }
            case RISE, LANDED -> {
                setHeight(0F);
                landed = motion() == ResourceDef.DropMotion.LANDED;
            }
        }
    }

    public void initializeSurface(LevelServer level) {
        if (surfaceInitialized) return;
        groundHeight = level.surfaceHeight(surfaceId(), cellX(), cellY());
        setHeight(height() + groundHeight);
        surfaceInitialized = true;
    }

    @Override
    public float renderScale() {
        return renderScale;
    }

    /** How this drop arrives. */
    public ResourceDef.DropMotion motion() {
        if (motionOverride != null) {
            return motionOverride;
        }
        return def.dropMotion() == null ? ResourceDef.DropMotion.FALL : def.dropMotion();
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
            advance(level, motion());
            return;
        }
        if (++expireTicks >= LIFETIME_TICKS) {
            remove();
        }
    }

    private void advance(LevelServer level, ResourceDef.DropMotion motion) {
        if (motion == ResourceDef.DropMotion.RISE) {
            // Up for half the arc, then back down. A sunflower's sun comes straight back down
            // onto the flower it came from (its scatter is zero); a coin is thrown a little to
            // one side on the way, because several of them burst out of one zombie at once.
            riseTicks++;
            float progress = Math.min(1F, riseTicks / (float) (ResourceDef.RISE_TICKS * 2F));
            // A parabola peaking at the resource's rise height halfway through.
            setCellX(riseOriginX + driftX * progress);
            groundHeight = level.surfaceHeight(surfaceId(), cellX(), cellY());
            setHeight(groundHeight + def.riseHeight() * 4F * progress * (1F - progress));
            if (riseTicks >= ResourceDef.RISE_TICKS * 2) {
                setCellX(riseOriginX + driftX);
                setHeight(groundHeight);
                landed = true;
                setAnimation(EntityAnimations.LANDED);
            }
            return;
        }
        if (motion == ResourceDef.DropMotion.LANDED) {
            landed = true;
            setAnimation(EntityAnimations.LANDED);
            return;
        }
        groundHeight = level.surfaceHeight(surfaceId(), cellX(), cellY());
        setHeight(height() - fallSpeed);
        if (height() <= groundHeight) {
            setHeight(groundHeight);
            landed = true;
            setAnimation(EntityAnimations.LANDED);
        }
    }

    @Override
    public CompoundTag saveState() {
        CompoundTag tag = saveBaseState();
        tag.putFloat("groundHeight", groundHeight);
        tag.putInt("surfaceInitialized", surfaceInitialized ? 1 : 0);
        tag.putInt("amount", amount);
        tag.putInt("landed", landed ? 1 : 0);
        tag.putInt("expireTicks", expireTicks);
        tag.putInt("collected", collected ? 1 : 0);
        // The arc's own state, so a save taken mid-flight resumes mid-flight instead of
        // restarting the throw from the ground.
        tag.putInt("riseTicks", riseTicks);
        tag.putFloat("riseOriginX", riseOriginX);
        tag.putFloat("driftX", driftX);
        tag.putFloat("renderScale", renderScale);
        return tag;
    }

    @Override
    public void restoreState(CompoundTag tag) {
        restoreBaseState(tag);
        groundHeight = tag.getFloat("groundHeight");
        surfaceInitialized = tag.getInt("surfaceInitialized") != 0;
        landed = tag.getInt("landed") != 0;
        expireTicks = tag.getInt("expireTicks");
        collected = tag.getInt("collected") != 0;
        riseTicks = tag.getInt("riseTicks");
        riseOriginX = tag.contains("riseOriginX") ? tag.getFloat("riseOriginX") : cellX();
        driftX = tag.getFloat("driftX");
        // A save written before drops carried a size of their own has no key; NBT's getFloat
        // would hand back 0, which is not a size, so the default stands in for it.
        float savedScale = tag.getFloat("renderScale");
        renderScale = savedScale <= 0F
                ? com.pvzce.common.network.packet.EntitySpawnS2C.DEFAULT_SCALE
                : savedScale;
    }
}
