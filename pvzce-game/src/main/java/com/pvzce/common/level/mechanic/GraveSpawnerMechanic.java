package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.GraveSpawnerData;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.level.SceneGrid;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * Graves that keep giving up their dead: the original's Whack-a-Zombie.
 *
 * <p>2-5 is the level this exists for. Its zombies do not walk in off the road - they climb out
 * of the gravestones, one grave at a time, for as long as the level runs, and the graves a
 * player smashes for breathing room come back. That is a different shape from every other night
 * level, where the graves are scenery that opens once at the last wave, which is why it is a
 * mechanic rather than another game rule.
 *
 * <p><strong>What it does not do</strong>: it raises no road zombies. A level using this still
 * gets whatever its {@code waves} say, so a level whose zombies all come out of the ground
 * writes its waves with empty {@code entries} - the waves then only carry the level's pacing,
 * its progress bar and its ending - and lets {@link #tick} do the work. A level that wants both
 * gets both.
 *
 * <p><strong>When it stops</strong>: the last wave. The field has to be able to fall to zero for
 * the level to be won, and a grave raising one zombie every second and a half never lets it. So
 * the rise clock is dropped once {@code waves} have all been released, while the graves
 * themselves keep being topped up to the standing count.
 *
 * <p>The run state is the countdown and the grave counter, kept in a {@link Rig} the level owns
 * (a mechanic instance is a shared registry entry, and two levels must not share one lawn's
 * clock). Both go into the save, so a resumed run keeps its rhythm instead of restarting the
 * interval.
 */
public final class GraveSpawnerMechanic implements LevelMechanic<GraveSpawnerData> {
    /** The NBT key this mechanic's run state is written under. */
    private static final String KEY_GRAVE_SPAWNER = "GraveSpawner";
    /**
     * How many graves may be raised in one tick.
     *
     * <p>Only reached by a blast that clears several at once (a cherry bomb over a cluster), and
     * capped because each rise picks a random free cell: without the cap a boom over a full lawn
     * turns into one very long tick. The rest go up on the ticks that follow.
     */
    private static final int MAX_RISES_PER_TICK = 2;

    @Override
    public MapCodec<GraveSpawnerData> codec() {
        return GraveSpawnerData.MAP_CODEC;
    }

    @Override
    public List<String> validate(LevelDef def, GraveSpawnerData data) {
        List<String> errors = new ArrayList<>(data.validate(def.width()));
        for (Identifier zombie : data.zombies()) {
            if (BuiltInRegistries.ZOMBIES.get(zombie) == null) {
                errors.add("grave_spawner names unknown zombie '" + zombie
                        + "': the graves would come up empty");
            }
        }
        if (data.minGraves() > 0 && !hasGraveOnTheBoard(def)) {
            errors.add("grave_spawner keeps " + data.minGraves()
                    + " graves up but the level paints none, so the first one would appear"
                    + " out of thin air mid-level");
        }
        return errors;
    }

    @Override
    public List<FieldSpec> editorFields() {
        return List.of(
                FieldSpec.integer("min_graves", "pvzce.mechanic.grave_spawner.field.min_graves", 0, 81),
                FieldSpec.integer("initial_graves", "pvzce.mechanic.grave_spawner.field.initial_graves",
                        GraveSpawnerData.INITIAL_AS_MINIMUM, 81),
                FieldSpec.integer("interval", "pvzce.mechanic.grave_spawner.field.interval", 1, 12000),
                FieldSpec.integer("min_x", "pvzce.mechanic.grave_spawner.field.min_x", 0, 64),
                FieldSpec.integer("max_x", "pvzce.mechanic.grave_spawner.field.max_x",
                        GraveSpawnerData.MAX_X_UNSET, 64),
                FieldSpec.integer("graves_per_wave", "pvzce.mechanic.grave_spawner.field.graves_per_wave",
                        0, 81));
    }

    /**
     * The zombies a grave can give up.
     *
     * <p>A level whose zombies all climb out of the ground writes its waves with empty entries, so
     * the wave table it previews from names nobody. This is the list it is actually sending.
     */
    @Override
    public List<Identifier> previewZombieIds(LevelDef def, GraveSpawnerData data) {
        return List.copyOf(data.zombies());
    }

    @Override
    public void onLevelCreated(LevelServer level, GraveSpawnerData data) {
        // Built eagerly so the save's countdown has somewhere to land and the opening board's
        // own graves are counted before the first tick.
        rig(level, data);
    }

    @Override
    public void tick(LevelServer level, GraveSpawnerData data) {
        Rig rig = rig(level, data);
        if (data.phased()) {
            tickPhased(level, data, rig);
            return;
        }
        // Once the level has announced its last wave the lawn stops gaining tombstones.
        //
        // That wave is the graves' farewell: every stone standing at that moment gives up one
        // zombie (`LevelServer.riseGraveZombies`), and it happens exactly once. A stone raised
        // after it is a stone that can never open - indistinguishable from the ones that did,
        // so the player reads it as the farewell having skipped one - which is why the refill
        // has to stop here rather than keep topping the lawn up for the rest of the level.
        // The trickle of zombies stops for its own reason (see below); this is about the holes.
        if (level.wavesReleased()) {
            return;
        }
        // The opening board first: a level paints its own graves, and this raises whatever
        // shortfall is left over before anything starts coming out of them. The target is the
        // *initial* count until it has been reached, and the standing target forever after -
        // so a block that opens with nine graves and settles at five does not spend the whole
        // level putting the ninth one back.
        //
        // The standing target grows with the waves that have arrived (see
        // GraveSpawnerData#standingTarget): every wave the level announces also asks the lawn
        // for more holes, which is how Whack-a-Zombie gets harder without a single road zombie.
        int standing = data.standingTarget(level.currentWave());
        int target = rig.raised < data.initialFor()
                ? Math.max(standing, data.initialFor())
                : standing;
        keepGravesUp(level, data, rig, target);
        // The graves stop giving up their dead once the level has sent its last wave. Without
        // this the field could never fall to zero - the level is won by clearing the board after
        // the final wave - and a 90-tick rise clock makes that a race no player can win. A level
        // with no waves at all (an endless sandbox) is unaffected, because "every wave released"
        // is false while there are none to release.
        if (data.zombies().isEmpty()) {
            return;
        }
        if (--rig.ticksUntilRise > 0) {
            return;
        }
        rig.ticksUntilRise = data.interval();
        List<SceneGrid.Cell<SceneElementDef>> graves = level.graveCells();
        if (graves.isEmpty()) {
            return;
        }
        SceneGrid.Cell<SceneElementDef> chosen = graves.get(level.random().nextInt(graves.size()));
        Identifier zombie = data.zombies().get(level.random().nextInt(data.zombies().size()));
        level.raiseZombieFromGrave(zombie, chosen.x(), chosen.y());
    }

    /** The minigame's progression: ordinary first, cones later, buckets and bursts last. */
    private static void tickPhased(LevelServer level, GraveSpawnerData data, Rig rig) {
        int wave = level.currentWave();
        if (level.wavesReleased()) {
            finalBurst(level, data);
            return;
        }
        if (wave != rig.lastWave) {
            rig.lastWave = wave;
            rig.graveTarget = Math.max(data.minGraves(), level.graveCells().size() + (wave > 0 ? 1 : 0));
        }
        keepGravesUp(level, data, rig, Math.max(rig.graveTarget,
                rig.raised < data.initialFor() ? data.initialFor() : 0));
        if (wave == 0 || --rig.ticksUntilRise > 0) return;
        int phase = Math.min(5, Math.max(0, (wave - 1) * 6 / level.def().waves().size()));
        int countRoll = level.random().nextInt(100);
        int count = countRoll < PvzceConstants.WHACK_TRIPLE_CHANCE.get(phase) ? 3
                : countRoll < PvzceConstants.WHACK_TRIPLE_CHANCE.get(phase) + PvzceConstants.WHACK_DOUBLE_CHANCE.get(phase) ? 2 : 1;
        int typeRoll = level.random().nextInt(100);
        Identifier id = PvzceIds.id(typeRoll < PvzceConstants.WHACK_BUCKET_CHANCE.get(phase) && count < 3 ? "buckethead_zombie"
                : typeRoll < PvzceConstants.WHACK_BUCKET_CHANCE.get(phase) + PvzceConstants.WHACK_CONE_CHANCE.get(phase) ? "conehead_zombie" : "basic_zombie");
        var graves = availableGraves(level);
        java.util.Collections.shuffle(graves, level.random());
        for (int i = 0; i < Math.min(count, graves.size()); i++) {
            var grave = graves.get(i);
            raisePhased(level, id, grave.x(), grave.y(), false);
        }
        float progress = (wave - 1F) / Math.max(1, level.def().waves().size() - 1);
        int minimum = Math.round(PvzceConstants.WHACK_INITIAL_INTERVAL
                + (PvzceConstants.WHACK_FINAL_INTERVAL - PvzceConstants.WHACK_INITIAL_INTERVAL) * progress);
        rig.ticksUntilRise = minimum + level.random().nextInt(minimum + 1);
    }

    public static void finalBurst(LevelServer level, GraveSpawnerData data) {
        Rig rig = rig(level, data);
        if (rig.finalBurst) return;
        rig.finalBurst = true;
        for (var grave : availableGraves(level)) {
            raisePhased(level, PvzceIds.id(level.random().nextBoolean()
                    ? "conehead_zombie" : "buckethead_zombie"), grave.x(), grave.y(), true);
        }
    }

    private static List<SceneGrid.Cell<SceneElementDef>> availableGraves(LevelServer level) {
        List<SceneGrid.Cell<SceneElementDef>> result = new ArrayList<>();
        for (var grave : level.graveCells()) {
            var plant = level.plantAt(grave.x(), grave.y());
            if (plant == null || !plant.defId().equals(PvzceIds.id("grave_buster"))) result.add(grave);
        }
        return result;
    }

    private static void raisePhased(LevelServer level, Identifier id, int x, int y, boolean finalBurst) {
        var zombie = level.raiseZombieFromGrave(id, x, y);
        if (zombie == null) return;
        float progress = Math.max(0, level.currentWave() - 1F)
                / Math.max(1, level.def().waves().size() - 1);
        float maximum = finalBurst ? 2F : 1F + 2F * progress * progress;
        zombie.setMovementMultiplier(2F * (0.5F + level.random().nextFloat() * (maximum - 0.5F)));
    }

    @Override
    public void collectSave(LevelServer level, GraveSpawnerData data, CompoundTag root) {
        Rig rig = rig(level, data);
        CompoundTag tag = new CompoundTag();
        tag.putInt("TicksUntilRise", rig.ticksUntilRise);
        tag.putInt("Raised", rig.raised);
        tag.putInt("NextDesign", rig.nextDesign);
        tag.putInt("LastWave", rig.lastWave);
        tag.putInt("GraveTarget", rig.graveTarget);
        tag.putByte("FinalBurst", (byte) (rig.finalBurst ? 1 : 0));
        root.put(KEY_GRAVE_SPAWNER, tag);
    }

    @Override
    public void applySave(LevelServer level, GraveSpawnerData data, CompoundTag root) {
        Rig rig = rig(level, data);
        CompoundTag tag = root.getCompound(KEY_GRAVE_SPAWNER);
        // A save written before this mechanic existed has no block, and the rig then keeps the
        // values it was built with - the same shape every other mechanic's applySave has.
        if (tag == null) {
            return;
        }
        rig.ticksUntilRise = Math.max(1, tag.getInt("TicksUntilRise"));
        rig.raised = Math.max(0, tag.getInt("Raised"));
        rig.nextDesign = Math.max(0, tag.getInt("NextDesign"));
        rig.lastWave = tag.getInt("LastWave");
        rig.graveTarget = tag.getInt("GraveTarget");
        rig.finalBurst = tag.getInt("FinalBurst") != 0;
    }

    // ------------------------------------------------------------------
    // Run state and helpers
    // ------------------------------------------------------------------

    /**
     * Tops the lawn back up to {@code target} graves.
     *
     * <p>Called every tick, because a grave can be smashed at any moment and the refill has to
     * notice. In the common case it is one count against one number, which is why it is not
     * event-driven: there is no event for "a grave is gone" - the shovel does not know what a
     * grave is, and the grave buster removes the one it is standing on.
     *
     * @return true when at least one grave was raised
     */
    private static boolean keepGravesUp(LevelServer level, GraveSpawnerData data, Rig rig, int target) {
        if (target <= 0) {
            return false;
        }
        int present = level.graveCells().size();
        if (present >= target) {
            return false;
        }
        int wanted = Math.min(MAX_RISES_PER_TICK, target - present);
        // The scatter is shared with `grave_field`, which lays the opening board out: what a
        // grave is, where it may stand and which design comes next are one implementation.
        GraveScatter.Placement placed = GraveScatter.place(level, data.minX(),
                data.maxXFor(level.width()), GraveScatter.DESIGNS, wanted, rig.nextDesign);
        rig.nextDesign = placed.nextDesign();
        rig.raised += placed.raised();
        return placed.raised() > 0;
    }

    /** True when the level paints at least one gravestone of its own. */
    private static boolean hasGraveOnTheBoard(LevelDef def) {
        for (var entry : def.scene().entrySet()) {
            SceneElementDef element = BuiltInRegistries.SCENE_ELEMENTS.get(entry.getKey());
            if (element != null && PvzceIds.SURFACE_GRAVE.equals(element.surfaceClass())) {
                return true;
            }
        }
        return false;
    }

    /** This level's grave clock. */
    private static Rig rig(LevelServer level, GraveSpawnerData data) {
        return level.mechanicState(PvzceIds.MECHANIC_GRAVE_SPAWNER, () -> new Rig(data));
    }

    /** The countdown to the next rise, and how many graves this level has raised so far. */
    private static final class Rig {
        private int ticksUntilRise;
        private int raised;
        private int nextDesign;
        private int lastWave;
        private int graveTarget;
        private boolean finalBurst;

        private Rig(GraveSpawnerData data) {
            this.ticksUntilRise = data.interval();
        }
    }
}
