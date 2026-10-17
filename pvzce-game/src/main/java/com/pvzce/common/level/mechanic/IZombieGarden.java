package com.pvzce.common.level.mechanic;

import com.pvzce.api.content.PlantGardenData;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.capability.plant.ExplosiveCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Original brain puzzles, hosted by the plant garden mechanic. No wave director or plant AI. */
public final class IZombieGarden {
    private IZombieGarden() { }

    public record Progress(int round, int eatenRows) {
        public static final PacketStruct.Codec<Progress> CODEC = PacketStruct.<Progress>builder()
                .field(Progress::round, com.pvzce.common.network.PacketByteBuf::writeInt, com.pvzce.common.network.PacketByteBuf::readInt)
                .field(Progress::eatenRows, com.pvzce.common.network.PacketByteBuf::writeInt, com.pvzce.common.network.PacketByteBuf::readInt)
                .build(v -> new Progress((Integer) v.get(0), (Integer) v.get(1)));
    }

    private static final class State {
        int round;
        int eatenRows;
        int delay;
        final int[] bites = new int[5];
        boolean dirty = true;
    }

    private static State state(LevelServer level) {
        return level.mechanicState(PvzceIds.MECHANIC_PLANT_GARDEN, State::new);
    }

    public static void create(LevelServer level, PlantGardenData data) {
        State state = new State();
        level.setMechanicState(PvzceIds.MECHANIC_PLANT_GARDEN, state);
        layOut(level, data, state);
    }

    public static void tick(LevelServer level, PlantGardenData data) {
        State state = state(level);
        if (data.endless() && state.eatenRows == (1 << level.height()) - 1 && --state.delay <= 0) {
            // A completed board is replaced even if a skipped plant is still standing.
            for (var entity : level.entities())
                if (!(entity instanceof com.pvzce.server.entity.ResourceDropEntity)) entity.remove();
            level.flushPending();
            state.round++;
            state.eatenRows = 0;
            java.util.Arrays.fill(state.bites, 0);
            layOut(level, data, state);
            state.dirty = true;
        }
        if (state.dirty) {
            sendState(level, level::send);
            state.dirty = false;
        }
    }

    /** Stops at a brain and shares its chewing progress between all zombies in that lane. */
    public static boolean eatBrain(LevelServer level, ZombieEntity zombie) {
        if (!level.isBrainPuzzle() || zombie.walkDirection() > 0 || zombie.cellX() > -0.4F) return false;
        State state = state(level);
        int row = zombie.gridY();
        int bit = 1 << row;
        if ((state.eatenRows & bit) != 0) {
            zombie.remove();
            return true;
        }
        zombie.setAnimation(EntityAnimations.EAT);
        if (!zombie.frozen() && !zombie.buttered()
                && ++state.bites[row] >= PvzceConstants.I_ZOMBIE_BRAIN_EAT_TICKS) {
            reachedLeft(level, zombie);
        }
        return true;
    }

    public static void reachedLeft(LevelServer level, ZombieEntity zombie) {
        State state = state(level);
        state.eatenRows |= 1 << zombie.gridY();
        state.dirty = true;
        zombie.remove();
        level.emitEffect("", zombie.cellX(), zombie.cellY(), PvzceSounds.EFFECT_BITE);
        if (state.eatenRows == (1 << level.height()) - 1) {
            PlantGardenData data = LevelMechanics.dataOf(level.def(), PvzceIds.MECHANIC_PLANT_GARDEN,
                    PlantGardenData.class).orElseThrow();
            if (data.endless()) state.delay = data.initialDelayTicks();
            else level.declareVictory();
        }
    }

    /** Each original sunflower yields eight 25-sun drops as its 300 health is consumed. */
    public static void plantDamaged(LevelServer level, PlantEntity plant, int previousHealth) {
        if (!plant.defId().equals(PvzceIds.id("sunflower"))) return;
        int drops = previousHealth / PvzceConstants.I_ZOMBIE_SUN_HEALTH_STEP
                - plant.health() / PvzceConstants.I_ZOMBIE_SUN_HEALTH_STEP;
        if (plant.health() == 0) drops++;
        dropSun(level, plant, drops);
    }

    public static void dropRemainingSun(LevelServer level, PlantEntity plant) {
        if (plant.defId().equals(PvzceIds.id("sunflower")))
            dropSun(level, plant, plant.health() / PvzceConstants.I_ZOMBIE_SUN_HEALTH_STEP + 1);
    }

    private static void dropSun(LevelServer level, PlantEntity plant, int drops) {
        for (int i = 0; i < drops; i++) {
            level.spawnProducedResource(PvzceIds.SUN, PvzceConstants.SUN_VALUE,
                    plant.cellX(), plant.cellY(), level.team(level.humanTeamId()), 1F,
                    (i - (drops - 1) / 2F) * PvzceConstants.PRODUCER_DROP_SPACING_CELLS, plant.surfaceId());
        }
    }

    private static void layOut(LevelServer level, PlantGardenData data, State state) {
        List<PlantGardenData.Round> groups = data.endless() ? endlessFormation(level, state.round) : data.formation();
        for (PlantGardenData.Round group : groups) {
            Identifier plant = group.pool().get(0);
            List<int[]> cells = new ArrayList<>();
            for (int row = 0; row < level.height(); row++) {
                if (!group.rows().isEmpty() && !group.rows().contains(row)) continue;
                for (int x = 0; x < data.columns(); x++) {
                    if ((plant.equals(PvzceIds.id("wall_nut")) || plant.equals(PvzceIds.id("torchwood")))
                            && x < data.columns() - 3) continue;
                    if (level.plantsAt(x, row).isEmpty()) cells.add(new int[]{x, row});
                }
            }
            Collections.shuffle(cells, level.random());
            for (int i = 0; i < Math.min(group.count(), cells.size()); i++) {
                int[] cell = cells.get(i);
                level.spawnPlant(BuiltInRegistries.PLANTS.get(plant), level.team(PvzceIds.PLANT_TEAM), cell[0], cell[1]);
            }
        }
        for (var entity : level.entities()) {
            if (entity instanceof PlantEntity plant) {
                plant.wake();
                ExplosiveCapability explosive = plant.capability(ExplosiveCapability.class);
                if (explosive != null) explosive.detonateNow();
            }
        }
    }

    // Original endless distributions: eight economy/mushroom cells, seventeen defence cells.
    private static List<PlantGardenData.Round> endlessFormation(LevelServer level, int round) {
        List<PlantGardenData.Round> result = new ArrayList<>();
        int formation = level.random().nextInt(5);
        int min = Math.min(round >= 10 ? 5 : 4, 2 + round / 3);
        int max = Math.min(round >= 10 ? 7 : 6, 3 + round / 2);
        int puffs = round == 0 ? 0 : round == 1 ? 1 : min + level.random().nextInt(max - min + 1);
        add(result, "sunflower", 8 - puffs); add(result, "puff_shroom", puffs);
        if (formation == 0 && round >= 1) {
            switch (level.random().nextInt(5)) {
                case 0 -> add(result, "snow_pea", 9, "split_pea", 4, "repeater", 4);
                case 1 -> add(result, "potato_mine", 9, "chomper", 8);
                case 2 -> add(result, "spikeweed", 9, "starfruit", 8);
                case 3 -> add(result, "fume_shroom", 9, "magnet_shroom", 8);
                default -> add(result, "scaredy_shroom", 12, "sunflower", 5);
            }
        } else {
            switch (level.random().nextInt(6)) {
                case 0, 1, 2 -> add(result, "wall_nut", 1, "torchwood", 1, "potato_mine", 1, "chomper", 2,
                        "pea_shooter", 1, "split_pea", 1, "kernel_pult", 1, "threepeater", 1, "snow_pea", 1,
                        "squash", 1, "fume_shroom", 1, "umbrella_leaf", 1, "starfruit", 1, "magnet_shroom", 1, "spikeweed", 2);
                case 3, 4 -> add(result, "torchwood", 1, "split_pea", 3, "repeater", 1, "kernel_pult", 3,
                        "threepeater", 1, "snow_pea", 3, "umbrella_leaf", 1, "magnet_shroom", 1, "spikeweed", 3);
                default -> add(result, "potato_mine", 4, "chomper", 3, "squash", 3, "fume_shroom", 4, "spikeweed", 3);
            }
        }
        return result;
    }

    private static void add(List<PlantGardenData.Round> groups, Object... pairs) {
        for (int i = 0; i < pairs.length; i += 2)
            groups.add(new PlantGardenData.Round(List.of(PvzceIds.id((String) pairs[i])), (Integer) pairs[i + 1], 0, List.of()));
    }

    public static void sendState(LevelServer level, LevelServer.ServerBridge bridge) {
        State state = state(level);
        bridge.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_PLANT_GARDEN, Progress.CODEC,
                new Progress(state.round, state.eatenRows)));
    }

    public static boolean changingBoard(LevelServer level) {
        State state = level.mechanicStateOrNull(PvzceIds.MECHANIC_PLANT_GARDEN, State.class);
        return state != null && state.delay > 0;
    }

    public static void save(LevelServer level, CompoundTag root) {
        State state = state(level);
        CompoundTag saved = new CompoundTag();
        saved.putInt("Round", state.round); saved.putInt("EatenRows", state.eatenRows); saved.putInt("Delay", state.delay);
        for (int row = 0; row < state.bites.length; row++) saved.putInt("Bites" + row, state.bites[row]);
        root.put("BrainGarden", saved);
    }

    public static void load(LevelServer level, CompoundTag root) {
        if (!root.contains("BrainGarden")) return;
        State state = state(level);
        CompoundTag saved = root.getCompound("BrainGarden");
        state.round = saved.getInt("Round"); state.eatenRows = saved.getInt("EatenRows"); state.delay = saved.getInt("Delay");
        for (int row = 0; row < state.bites.length; row++) state.bites[row] = saved.getInt("Bites" + row);
        state.dirty = true;
    }
}
