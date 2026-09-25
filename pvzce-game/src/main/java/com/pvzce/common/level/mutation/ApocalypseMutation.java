package com.pvzce.common.level.mutation;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.capability.plant.ExplosiveCapability;
import com.pvzce.common.core.PlantPlacement;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.level.LevelServer;

/**
 * The Doom-shroom arrives everywhere at once, and then it is only an entry on the panel.
 *
 * <p>One shot, at the instant it appears: every plantable cell on the board gets a Doom-shroom,
 * they all detonate on the fuse the shipped definition declares (90 ticks), and the craters they
 * leave are the mutation's real legacy. Afterwards the entry occupies a slot in the list and does
 * nothing at all - which is what "之后就只占位，不生效" asks for, and is also the only sane behaviour:
 * a second wave of Doom-shrooms would be a board the player could never stand on again.
 *
 * <p>Cells that already hold a plant are included on purpose. The blast is the point, and skipping
 * occupied cells would mean the mutation did the most damage exactly where the player had built
 * the least. The level places these itself, so the placement restrictions that apply to the player
 * do not apply here.
 */
final class ApocalypseMutation implements Mutation {
    @Override
    public Identifier id() {
        return PvzceIds.MUTATION_APOCALYPSE;
    }

    @Override
    public boolean canRun(LevelServer level) {
        return BuiltInRegistries.PLANTS.get(PvzceIds.DOOM_SHROOM) != null
                && level.plantPlayer() != null;
    }

    /**
     * On a restore the mushrooms are <em>not</em> summoned again, and that is the whole point of
     * the flag this mutation saves: the craters of its one shot are terrain, and a resumed run that
     * fired a second wave would crater a lawn the player has since rebuilt.
     */
    @Override
    public Object applyFromSave(LevelServer level, Mutation.Roll roll) {
        return new Applied(firedPlaced);
    }

    @Override
    public CompoundTag saveState() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Placed", savingState instanceof Applied applied ? applied.placed() : 0);
        return tag;
    }

    @Override
    public void loadState(CompoundTag tag) {
        this.firedPlaced = tag == null ? 0 : Math.max(0, tag.getInt("Placed"));
    }

    /** What the one shot did, set by the manager around {@link #saveState}. */
    private Applied savingState;
    /** What {@link #loadState} read; the field a restore carries into its state. */
    private int firedPlaced;

    /** How the manager hands this mutation the state it should write down. */
    void savingState(Object state) {
        this.savingState = state instanceof Applied applied ? applied : null;
    }

    @Override
    public Object apply(LevelServer level, Mutation.Roll roll) {
        PlantDef doom = BuiltInRegistries.PLANTS.get(PvzceIds.DOOM_SHROOM);
        if (doom == null || level.plantPlayer() == null) {
            return null;
        }
        int placed = 0;
        for (int x = 0; x < level.width(); x++) {
            for (int y = 0; y < level.height(); y++) {
                if (!isPlantable(level, x, y)) {
                    continue;
                }
                PlantEntity shroom = level.spawnPlant(doom, level.plantPlayer().team(), x, y);
                arm(shroom);
                placed++;
            }
        }
        return new Applied(placed);
    }

    // revert does nothing on purpose: the craters it leaves are terrain, and a mushroom that has
    // already exploded cannot be un-exploded. The mutation's whole effect happened in the tick it
    // arrived, which is exactly why the panel keeps showing it as a placeholder afterwards.

    /**
     * Wakes a summoned Doom-shroom and sends it off.
     *
     * <p>Both halves are needed and neither is a detail:
     *
     * <ul>
     *   <li><b>Waking it.</b> A Doom-shroom is a mushroom, and mushrooms sleep in daylight - and
     *       this mutation's level is a <em>day</em> pool. A summon that left it asleep stood there
     *       with its ninety-tick fuse frozen (a sleeping plant is not ticked), so the apocalypse
     *       summoned a lawn of furniture instead of a blast. Waking is the plant's own hook, so any
     *       future summon that has a sleeping state is handled the same way rather than by a list
     *       of plant ids here.</li>
     *   <li><b>Sending it off.</b> "Summoned" reads as "it happens now": the player watches the
     *       lawn fill with mushrooms and they go off together, rather than ninety ticks of a board
     *       that cannot be played.</li>
     * </ul>
     */
    private static void arm(PlantEntity shroom) {
        if (shroom == null) {
            return;
        }
        shroom.wake();
        ExplosiveCapability explosive = shroom.capability(ExplosiveCapability.class);
        if (explosive != null) {
            explosive.detonateNow();
        }
    }

    /**
     * Whether a Doom-shroom belongs in this cell.
     *
     * <p>The engine's own terrain rule, asked without the placement restrictions: a Doom-shroom is
     * an ordinary ground plant, so it wants what a Peashooter wants - plantable ground or a
     * plantable carrier, never water and never bare roof.
     */
    private static boolean isPlantable(LevelServer level, int x, int y) {
        SceneElementDef base = level.sceneAt(x, y);
        return base != null && PlantPlacement.terrainTagged(PlantPlacement.Terrain.of(base),
                PvzceTags.SCENE_PLANTABLE);
    }

    /** What the single shot did, so the state is not a bare {@code null}. */
    private record Applied(int placed) {
    }
}
