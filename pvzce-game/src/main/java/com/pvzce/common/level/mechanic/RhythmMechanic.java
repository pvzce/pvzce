package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.RhythmChartData;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rhythm chart, played: notes fall, presses are judged, and the lanes attack.
 *
 * <p>This is the server half, and the server is the one that <em>scores</em>. The client judges -
 * it is the side that knows how many milliseconds late the key was - and sends
 * {@code RhythmHitC2S} naming the note it believes it hit and how well. Everything the client
 * cannot be trusted with is here: whether that note exists, whether it has already been counted,
 * whether the press was near it at all, and what a counted note does to the lawn.
 *
 * <h2>The clock is the level's own tick count</h2>
 *
 * <p>Not wall time: a level that was paused, or one that was saved and resumed, has a tick count
 * that is the same number on both sides, and a chart written against it survives both. That is
 * also why the speed control is refused on a rhythm level (see {@code LevelServer.forbidsSpeedChange}):
 * a 2x level would not move the notes, it would halve the time the player has to react to them.
 */
public final class RhythmMechanic implements LevelMechanic<RhythmChartData> {
    /** Which notes have been counted, and the run's tally. */
    private static final class State {
        /** Lane key -> the notes already judged, so one note cannot score twice. */
        final Map<String, java.util.Set<Integer>> judged = new HashMap<>();
        int perfect;
        int good;
        int missed;
        int combo;
        int bestCombo;
        /** The first note of each lane that has not been judged; the chart is ascending. */
        final Map<String, Integer> next = new HashMap<>();
        /** Lane key -> the ticks that lane really has, so a press can be checked against them. */
        final Map<String, java.util.Set<Integer>> notes = new HashMap<>();
        /**
         * The level tick the chart started on, or -1 while it has not.
         *
         * <p>Not zero, and not the level's own count: a rhythm level opens in its build phase, and
         * a chart measured from the level's first tick would miss its own first notes while the
         * player was still placing a peashooter. The build phase <em>is</em> the chart's buffer, so
         * the clock starts when the player says the waves may start - which is also why the first
         * note is a few seconds into the chart rather than on beat zero.
         */
        int startTick = -1;
    }

    private static State state(LevelServer level) {
        return level.mechanicState(PvzceIds.MECHANIC_RHYTHM, State::new);
    }

    /** One lane's identity on the wire and in the judged set. */
    public static String laneKey(RhythmChartData.LaneKind kind, int index) {
        return kind.json() + ":" + index;
    }

    @Override
    public MapCodec<RhythmChartData> codec() {
        return RhythmChartData.MAP_CODEC;
    }

    @Override
    public void onLevelCreated(LevelServer level, RhythmChartData data) {
        level.setMechanicState(PvzceIds.MECHANIC_RHYTHM, new State());
    }

    @Override
    public void tick(LevelServer level, RhythmChartData data) {
        State state = state(level);
        if (!level.gameState().equals(GameStateS2C.RUNNING)) {
            return;
        }
        if (com.pvzce.common.level.mechanic.PreparationMechanic.isPreparing(level)) {
            // The build phase: the chart has not started, and nothing may be missed yet.
            state.startTick = -1;
            return;
        }
        if (state.startTick < 0) {
            state.startTick = level.tickCount();
        }
        int tick = level.tickCount() - state.startTick;
        for (RhythmChartData.Lane lane : data.lanes()) {
            String key = laneKey(lane.kind(), lane.index());
            List<Integer> ticks = data.ticksOf(lane);
            java.util.Set<Integer> judged = state.judged.computeIfAbsent(key,
                    ignored -> new java.util.HashSet<>());
            int from = state.next.getOrDefault(key, 0);
            // A note whose window has closed with nothing pressed is a miss, and it breaks the
            // combo - which is the whole difficulty of the mode: the chart does not wait.
            while (from < ticks.size() && ticks.get(from) + data.goodTicks() < tick) {
                int note = ticks.get(from);
                if (judged.add(note)) {
                    state.missed++;
                    state.combo = 0;
                }
                from++;
            }
            state.next.put(key, from);
        }
    }

    /**
     * Counts one press, or refuses it.
     *
     * <p>The refusals are what make the packet safe to accept: an unknown lane, a note that does not
     * exist, a note that has already been counted, or a press more than {@code goodTicks} away from
     * the note <em>by the server's own clock</em>. The last one is the one that matters - the client
     * says how well it did, and the server says whether it was anywhere near.
     *
     * @param perceivedTicks how far off the client believed it was, in ticks; the sign is ignored
     * @return {@code true} when the press was counted
     */
    public boolean judge(LevelServer level, RhythmChartData data, String laneKind, int laneIndex,
                         int noteTick, int perceivedTicks) {
        State state = state(level);
        if (!level.gameState().equals(GameStateS2C.RUNNING)) {
            return false;
        }
        RhythmChartData.Lane lane = findLane(data, laneKind, laneIndex);
        if (lane == null) {
            return false;
        }
        if (com.pvzce.common.level.mechanic.PreparationMechanic.isPreparing(level)) {
            return false;
        }
        if (state.startTick < 0) {
            state.startTick = level.tickCount();
        }
        int tick = level.tickCount() - state.startTick;
        int delta = Math.abs(tick - noteTick);
        if (delta > data.goodTicks()) {
            // The press was nowhere near the note the client named. Not counted, and *not* a miss
            // either: the note keeps its own window and will be missed by `tick` if nothing else
            // comes - so a client cannot farm misses, and a laggy one is not punished twice.
            return false;
        }
        // And the tick has to be a note the lane actually has. Without this the window itself is
        // the only limit, and a client could score nineteen times inside one note's window by
        // naming nineteen ticks that are near it - the note exists, the presses are "on time", and
        // none of them was ever written down.
        if (!notesOf(state, data, lane).contains(noteTick)) {
            return false;
        }
        String key = laneKey(lane.kind(), lane.index());
        java.util.Set<Integer> judged = state.judged.computeIfAbsent(key,
                ignored -> new java.util.HashSet<>());
        if (!judged.add(noteTick)) {
            return false;
        }
        boolean perfect = delta <= data.perfectTicks()
                || Math.abs(perceivedTicks) <= data.perfectTicks();
        if (perfect) {
            state.perfect++;
            if (data.perfectSun() > 0 && level.team(level.humanTeamId()) != null) {
                level.team(level.humanTeamId()).addResource(PvzceIds.SUN, data.perfectSun());
                level.send(new com.pvzce.common.network.packet.ResourceDeltaS2C(
                        level.humanTeamId().toString(), PvzceIds.SUN.toString(),
                        level.team(level.humanTeamId()).resourcesOf(PvzceIds.SUN)));
            }
        } else {
            state.good++;
        }
        state.combo++;
        state.bestCombo = Math.max(state.bestCombo, state.combo);
        attack(level, data, lane);
        return true;
    }

    /** One lane's note ticks as a set, computed once per lane and kept in the run's state. */
    private static java.util.Set<Integer> notesOf(State state, RhythmChartData data,
                                                  RhythmChartData.Lane lane) {
        String key = laneKey(lane.kind(), lane.index());
        return state.notes.computeIfAbsent(key,
                ignored -> new java.util.HashSet<>(data.ticksOf(lane)));
    }

    /** The lane's own attack: a row sweeps across, a column runs the length of the lawn. */
    private static void attack(LevelServer level, RhythmChartData data, RhythmChartData.Lane lane) {
        if (data.attackDamage() <= 0) {
            return;
        }
        com.pvzce.api.content.DamageTypeDef type = ZombieEntity.damageType(PvzceIds.DAMAGE_IMPACT);
        // The effect is emitted along the lane rather than at one point: a hit that fires a whole
        // row has to look like it fired the row, and a single puff in the middle reads as a plant
        // that happened to shoot. One per cell, and the sound once - on the first.
        if (lane.kind() == RhythmChartData.LaneKind.COL) {
            level.damageColumn(type, lane.index(), data.attackDamage(), level.team(PvzceIds.PLANT_TEAM));
            for (int y = 0; y < level.height(); y++) {
                level.emitEffect(com.pvzce.common.PvzceParticles.HIT_SPARK.toString(),
                        lane.index() + 0.5F, y + 0.5F,
                        y == 0 ? com.pvzce.common.PvzceSounds.EFFECT_EXPLOSION : null);
            }
        } else {
            level.damageRow(type, lane.index(), data.attackDamage(), level.team(PvzceIds.PLANT_TEAM));
            for (int x = 0; x < level.width(); x++) {
                level.emitEffect(com.pvzce.common.PvzceParticles.HIT_SPARK.toString(),
                        x + 0.5F, lane.index() + 0.5F,
                        x == 0 ? com.pvzce.common.PvzceSounds.EFFECT_EXPLOSION : null);
            }
        }
    }

    private static RhythmChartData.Lane findLane(RhythmChartData data, String kind, int index) {
        RhythmChartData.LaneKind wanted = RhythmChartData.LaneKind.fromJson(kind);
        for (RhythmChartData.Lane lane : data.lanes()) {
            if (lane.kind() == wanted && lane.index() == index) {
                return lane;
            }
        }
        return null;
    }

    /** The run's tally, for the HUD and for the summary. */
    public record Score(int perfect, int good, int missed, int combo, int bestCombo) {
    }

    public static Score score(LevelServer level) {
        State state = level.mechanicStateOrNull(PvzceIds.MECHANIC_RHYTHM, State.class);
        if (state == null) {
            return new Score(0, 0, 0, 0, 0);
        }
        return new Score(state.perfect, state.good, state.missed, state.combo, state.bestCombo);
    }

    @Override
    public List<String> validate(LevelDef def, RhythmChartData data) {
        List<String> errors = new ArrayList<>(data.validate());
        for (RhythmChartData.Lane lane : data.lanes()) {
            int limit = lane.kind() == RhythmChartData.LaneKind.COL ? def.width() : def.height();
            if (lane.index() >= limit) {
                errors.add("rhythm lane " + lane.kind().json() + " " + lane.index()
                        + " is off a " + def.width() + "x" + def.height() + " board");
            }
        }
        if (def.initialSun() < 0) {
            errors.add("rhythm chart on a level with negative sun");
        }
        return errors;
    }

    @Override
    public List<FieldSpec> editorFields() {
        // A chart is a list of lanes of note lists, which the field table cannot express; the
        // editor's rhythm page writes one instead (see `RhythmPage`).
        return List.of();
    }

    @Override
    public void collectSave(LevelServer level, RhythmChartData data, CompoundTag root) {
        Score score = score(level);
        root.putInt("RhythmPerfect", score.perfect());
        root.putInt("RhythmGood", score.good());
        root.putInt("RhythmMissed", score.missed());
        root.putInt("RhythmBestCombo", score.bestCombo());
        root.putInt("RhythmStart", state(level).startTick);
    }

    @Override
    public void applySave(LevelServer level, RhythmChartData data, CompoundTag root) {
        if (!root.contains("RhythmPerfect")) {
            return;
        }
        State state = state(level);
        state.perfect = Math.max(0, root.getInt("RhythmPerfect"));
        state.good = Math.max(0, root.getInt("RhythmGood"));
        state.missed = Math.max(0, root.getInt("RhythmMissed"));
        state.bestCombo = Math.max(0, root.getInt("RhythmBestCombo"));
        state.startTick = root.getInt("RhythmStart");
    }
}
