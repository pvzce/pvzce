package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.PlantGardenData;
import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.PvzceEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * I, Zombie's rounds: a lawn of the enemy's plants, and the next one when it is eaten.
 *
 * <p>The turn-over rule is the whole mechanic. A round ends when there is nothing of the garden
 * left <em>and</em> nothing of the player's is still walking - a zombie that is mid-lane when the
 * last plant dies is not a plant, and laying out the next garden under it would let the player
 * start the next puzzle with a head start they did not earn. Then the lawn is swept and replanted,
 * the player is paid the round's sun, and the count is announced.
 *
 * <p>Sweeping is not tidiness: the last zombie of a round is usually standing in the middle of the
 * garden, and a round that planted around it would be a round with fewer plants than it declared -
 * which is the same failure {@code ScaryPotterMechanic} documents for its pots.
 */
public final class PlantGardenMechanic implements LevelMechanic<PlantGardenData> {
    /** Which round is on the lawn, and whether the run has already been won. */
    private static final class State {
        int round;
        int delay;
        boolean cleared;
    }

    private static State state(LevelServer level) {
        return level.mechanicState(PvzceIds.MECHANIC_PLANT_GARDEN, State::new);
    }

    @Override
    public MapCodec<PlantGardenData> codec() {
        return PlantGardenData.MAP_CODEC;
    }

    @Override
    public void onLevelCreated(LevelServer level, PlantGardenData data) {
        State state = new State();
        level.setMechanicState(PvzceIds.MECHANIC_PLANT_GARDEN, state);
        if (!data.rounds().isEmpty()) {
            layOut(level, data.rounds().get(0));
            state.delay = data.initialDelayTicks();
        }
    }

    @Override
    public void tick(LevelServer level, PlantGardenData data) {
        State state = state(level);
        if (state.cleared) {
            return;
        }
        // The delay is the beat between "the garden died" and "here is the next one". It is not
        // decoration: the round is announced when the lawn is swept, and a garden that appeared on
        // the same tick would be planted under the announcement.
        if (state.delay > 0) {
            state.delay--;
            return;
        }
        if (gardenLeft(level) || zombiesLeft(level)) {
            return;
        }
        state.round++;
        if (state.round >= data.rounds().size()) {
            state.cleared = true;
            level.declareVictory();
            return;
        }
        PlantGardenData.Round round = data.rounds().get(state.round);
        LevelServer.LawnSweep swept = level.clearLawn();
        layOut(level, round);
        if (round.sun() > 0) {
            level.team(level.humanTeamId()).addResource(PvzceIds.SUN, round.sun());
            // The client's counter is a mirror of this number and nothing else pushes it here:
            // the round's sun is not a collect and not a purchase, so there is no other packet
            // that would carry it.
            level.send(new com.pvzce.common.network.packet.ResourceDeltaS2C(
                    level.humanTeamId().toString(), PvzceIds.SUN.toString(),
                    level.team(level.humanTeamId()).resourcesOf(PvzceIds.SUN)));
        }
        level.emitEffect("", level.width() / 2F, level.height() / 2F, PvzceSounds.AMBIENT_HUGE_WAVE);
        level.announceRound(state.round + 1, data.rounds().size(), swept);
        state.delay = data.initialDelayTicks();
    }

    /** True while any plant of the garden is still standing. */
    private static boolean gardenLeft(LevelServer level) {
        for (PvzceEntity entity : level.entities()) {
            // Removed is the whole test for a plant: one that has been eaten is flagged and swept
            // on the next tick, and "still in the list" is not "still standing".
            if (entity instanceof PlantEntity plant && !plant.isRemoved()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True while any zombie is still on the lawn, the player's or the level's.
     *
     * <p>Both, because a boss-summoned or otherwise spawned extra is still a body in the way, and
     * "the round is over" has to mean the board is quiet.
     */
    private static boolean zombiesLeft(LevelServer level) {
        for (PvzceEntity entity : level.entities()) {
            if (entity instanceof ZombieEntity zombie && !zombie.isRemoved() && zombie.isAlive()) {
                return true;
            }
        }
        return false;
    }

    /** Plants one round's worth of garden on free cells, at random. */
    private static void layOut(LevelServer level, PlantGardenData.Round round) {
        if (round.pool().isEmpty() || round.count() <= 0) {
            return;
        }
        Random random = level.random();
        List<int[]> cells = new ArrayList<>();
        for (int y : rows(level, round)) {
            for (int x = 0; x < level.width(); x++) {
                cells.add(new int[]{x, y});
            }
        }
        Collections.shuffle(cells, random);
        int placed = 0;
        // The pool is drawn from *with* replacement on purpose: a garden of six plants from a
        // three-plant pool is what "a wall of peashooters" means, and depleting the pool would
        // silently plant fewer than the round declared.
        for (int[] cell : cells) {
            if (placed >= round.count()) {
                break;
            }
            PlantDef def = BuiltInRegistries.PLANTS.get(
                    round.pool().get(random.nextInt(round.pool().size())));
            if (def == null) {
                continue;
            }
            if (level.spawnPlant(def, level.team(PvzceIds.PLANT_TEAM), cell[0], cell[1]) != null) {
                placed++;
            }
        }
    }

    private static List<Integer> rows(LevelServer level, PlantGardenData.Round round) {
        List<Integer> rows = new ArrayList<>();
        if (round.rows().isEmpty()) {
            for (int y = 0; y < level.height(); y++) {
                rows.add(y);
            }
            return rows;
        }
        for (int y : round.rows()) {
            if (y >= 0 && y < level.height()) {
                rows.add(y);
            }
        }
        return rows;
    }

    @Override
    public List<String> validate(LevelDef def, PlantGardenData data) {
        List<String> errors = new ArrayList<>(data.validate());
        List<Identifier> plants = new ArrayList<>();
        for (PlantGardenData.Round round : data.rounds()) {
            plants.addAll(round.pool());
        }
        for (Identifier plant : plants) {
            if (BuiltInRegistries.PLANTS.get(plant) == null) {
                errors.add("plant garden names '" + plant + "', which is not a registered plant");
            }
        }
        if (def.humanTeam().equals(PvzceIds.PLANT_TEAM)) {
            // A garden is the enemy's. On a level the player defends, it would plant the opposition
            // *on the player's own side* - the plants would shoot the player's plants' targets and
            // the round would never end.
            errors.add("This level lays out a plant garden but the player is on the plant side;"
                    + " a garden is I, Zombie's board (see LevelDef.humanTeam)");
        }
        return errors;
    }

    @Override
    public List<FieldSpec> editorFields() {
        // Rounds are a list of lists, which is the shape the field table cannot express - the same
        // reason the scary potter's are JSON-only.
        return List.of();
    }

    @Override
    public void collectSave(LevelServer level, PlantGardenData data, CompoundTag root) {
        State state = state(level);
        root.putInt("GardenRound", state.round);
        root.putInt("GardenDelay", state.delay);
        root.putByte("GardenCleared", (byte) (state.cleared ? 1 : 0));
    }

    @Override
    public void applySave(LevelServer level, PlantGardenData data, CompoundTag root) {
        State state = state(level);
        if (!root.contains("GardenRound")) {
            return;
        }
        state.round = Math.max(0, root.getInt("GardenRound"));
        state.delay = Math.max(0, root.getInt("GardenDelay"));
        state.cleared = root.getInt("GardenCleared") != 0;
    }
}
