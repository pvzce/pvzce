package com.pvzce.common.level.mechanic;

import com.mojang.serialization.MapCodec;
import com.pvzce.api.content.LevelDef;
import com.pvzce.api.content.RhythmChartData;
import com.pvzce.api.content.mechanic.FieldSpec;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.network.PacketByteBuf;
import com.pvzce.common.network.PacketStruct;
import com.pvzce.common.network.packet.GameStateS2C;
import com.pvzce.common.network.packet.MechanicSyncS2C;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The rhythm chart, played: notes are judged, and the column they name attacks.
 *
 * <p>This is the server half, and the server is the one that <em>scores</em>. The client judges -
 * it is the side that knows how many milliseconds late the key was - and sends
 * {@code RhythmHitC2S} naming the note it believes it hit and how well. Everything the client
 * cannot be trusted with is here: whether that note exists, whether it has already been counted,
 * whether the press was near it at all, and what a counted note does to the lawn.
 *
 * <h2>A counted note is an order to the plants</h2>
 *
 * <p>The lawn does not attack by itself on a rhythm level ({@code RhythmChartData#plantsHoldFire}):
 * a judged note tells every plant in that lane to attack, as many times as the verdict is worth -
 * three, two or one, and none for a miss (see {@code RhythmChartData#volleys}). A PERFECT also
 * drops the sun the chart pays out of one of those plants, as an entity that the player's own
 * collection rules pick up.
 *
 * <h2>The bar, and the streak that pays in fire</h2>
 *
 * <p>Two counters sit beside the tally, and they are what makes a run a run rather than a list of
 * notes played. The <b>energy bar</b> takes a hundred points from a PERFECT, fifty from a GOOD,
 * twenty from a FAIR and ten off for a miss nobody played, and bleeds five a second whatever the
 * player does; while it is at or above {@code PvzceConstants.ENERGY_DOUBLE_AT} every plant in the
 * game fires twice the bullets per attack, and at or above {@code ENERGY_TRIPLE_AT} three times.
 * The <b>consecutive-PERFECT streak</b> is the stricter counter - a GOOD breaks it exactly as a
 * miss does - and at 30, 50, 80 and 100 (and every twenty past that) it pays a jalapeno in every
 * row of the board.
 *
 * <p>Both live here rather than in a mechanic of their own because they are what a chart's notes
 * are <em>worth</em>, and a chart's notes are this mechanic's. They are also both the server's: the
 * client mirrors the streak and the verdict so the screen can answer a keypress on the same frame,
 * but every number the run is scored on - and every jalapeno - is decided here.
 *
 * <h2>The clock is the level's own tick count</h2>
 *
 * <p>Not wall time: a level that was paused, or one that was saved and resumed, has a tick count
 * that is the same number on both sides, and a chart written against it survives both. That is
 * also why the speed control is refused on a rhythm level (see {@code LevelServer.forbidsSpeedChange}):
 * a 2x level would not move the notes, it would halve the time the player has to react to them.
 */
public final class RhythmMechanic implements LevelMechanic<RhythmChartData> {
    /**
     * The chart's anchor and the run's numbers, as the client is told them.
     *
     * <p>The anchor - the level tick the chart counts from - is the first, and it is still on the
     * wire because the client cannot work it out: the chart starts when the player says the waves
     * may, and the client's mirror of that moment is a packet behind at best - and at worst absent,
     * since {@code ClientLevel.preparing()} is false before the preparation phase's own state has
     * arrived. A client that anchored on its own guess was therefore out by a hundred ticks on the
     * shipped levels (the level's first tick instead of the tick the player pressed 开始), and every
     * press it sent named a note the server was nowhere near: the film that found this showed the
     * client at combo five and the server's save at zero hits and twenty misses.
     *
     * <p>The <b>tally</b> rides it for a subtler reason, and one a film found: the two sides grade
     * the same press slightly differently by design - the server takes the better of its own
     * distance and the client's, so a press the client called GOOD can be a PERFECT here (see
     * {@link #judge}) - and a HUD that drew the client's counts beside this record's streak showed
     * {@code PERFECT 0 · 连续 PERFECT ×1}, which is not a difference anybody can read, only a
     * contradiction. So everything a player reads as "how the run is going" is counted once, here,
     * and the client's own counters are left to the one job they are better at: answering the press
     * on the frame it happened (see {@code RhythmPlay}).
     *
     * <p>Energy, streak and the jalapeno count ride the same message rather than one of their own
     * because a mechanic streams under <em>one</em> payload shape: {@code MechanicSyncS2C} is
     * dispatched by mechanic id alone (see {@code ClientMechanics}), so a second record under
     * {@code pvzce:rhythm} would be decoded as this one and read as nonsense. One record, sent
     * once when the anchor is taken and then on a cadence (see {@link #STATUS_INTERVAL_TICKS}).
     *
     * <p>{@code jalapenos} is a running total and not a flag: the screen announces the reward when
     * the number goes up, and a total cannot be missed by a dropped packet the way "one just
     * happened" can.
     */
    public record Status(int tick, int energy, int perfect, int good, int fair, int missed,
                         int combo, int bestCombo, int streak, int multiplier, int jalapenos) {
        public static final PacketStruct.Codec<Status> CODEC =
                PacketStruct.<Status>builder()
                        .field(Status::tick, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(Status::energy, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(Status::perfect, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(Status::good, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(Status::fair, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(Status::missed, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(Status::combo, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(Status::bestCombo, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(Status::streak, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(Status::multiplier, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .field(Status::jalapenos, PacketByteBuf::writeInt, PacketByteBuf::readInt)
                        .build(values -> new Status((Integer) values.get(0), (Integer) values.get(1),
                                (Integer) values.get(2), (Integer) values.get(3),
                                (Integer) values.get(4), (Integer) values.get(5),
                                (Integer) values.get(6), (Integer) values.get(7),
                                (Integer) values.get(8), (Integer) values.get(9),
                                (Integer) values.get(10)));
    }

    /**
     * How often the run's numbers are re-sent, in ticks.
     *
     * <p>Six - ten times a second. The bar bleeds a twelfth of a point per tick, so anything
     * slower would show the drop in visible steps, and anything faster would be a packet every
     * tick for a number the eye cannot resolve.
     */
    private static final int STATUS_INTERVAL_TICKS = 6;

    /** Which notes have been counted, and the run's tally. */
    private static final class State {
        /** Lane key -> the notes already judged, so one note cannot score twice. */
        final Map<String, java.util.Set<Integer>> judged = new HashMap<>();
        int perfect;
        int good;
        int fair;
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
        /** True once the anchor has been sent to the client, so it is told once and not per tick. */
        boolean startSynced;
        /** True once the track's end has been reached and the lawn swept, so it happens once. */
        boolean finished;
        /**
         * The energy bar, in points, kept fractional.
         *
         * <p>A {@code float} because the drain is five points a <em>second</em> - a twelfth of a
         * point a tick - and an integer bar that lost the remainder would either drain nothing or
         * drain six times too fast depending on which way it rounded. Clamped to
         * {@code [0, PvzceConstants.ENERGY_MAX]} on every change; what the client draws is the
         * rounded value (see {@link #statusOf}).
         */
        float energy;
        /**
         * How many notes in a row have been PERFECT.
         *
         * <p>Not {@link #combo}, which is the mode's older counter and counts every counted note
         * whatever its verdict. This one is the streak the reward is named after: a GOOD breaks it
         * exactly as a MISS does, which is what makes thirty of them mean something.
         */
        int perfectStreak;
        /** The streak length that pays next; starts at the first milestone. */
        int nextStreakReward = PvzceConstants.PERFECT_STREAK_MILESTONES[0];
        /**
         * How many whole-lawn volleys the streak has earned, as a running total.
         *
         * <p>The count the client banners off, and the reason {@link #pendingJalapenos} is a
         * separate field: a reward is <em>earned</em> in {@code judge}, which runs while the
         * server is handling packets, and <em>released</em> in {@link #tick}, which is the only
         * place the level can play an effect. The two are the same tick in the shipped game and
         * are still two fields, because "owed" and "paid" are two facts (see {@link #release}).
         */
        int streakRewards;
        /** Volleys earned and not yet fired; drained one per tick by {@link #release}. */
        int pendingJalapenos;
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
        anchor(level, state);
        int tick = level.tickCount() - state.startTick;
        // The bar bleeds before anything else is read, so a gate crossed this tick is crossed for
        // this tick's shots: mechanics run before entities (see LevelServer.tick), and a plant
        // firing later in the same tick reads the multiplier this drain decided on.
        state.energy = clampEnergy(state.energy
                - PvzceConstants.ENERGY_DRAIN_PER_SECOND / (float) PvzceConstants.TICKS_PER_SECOND);
        // And what the streak has earned is paid here rather than where it was earned: a reward is
        // crossed in `judge`, which runs while the server is handling packets - and a level can
        // play no effect then (see `release`).
        release(level, state);
        for (RhythmChartData.Lane lane : data.lanes()) {
            String key = laneKey(lane.kind(), lane.index());
            List<Integer> ticks = data.ticksOf(lane);
            java.util.Set<Integer> judged = state.judged.computeIfAbsent(key,
                    ignored -> new java.util.HashSet<>());
            int from = state.next.getOrDefault(key, 0);
            // A note whose window has closed with nothing pressed is a miss, and it breaks the
            // combo - which is the whole difficulty of the mode: the chart does not wait. It
            // breaks the PERFECT streak as well, and costs the bar its ten points: the two
            // counters are the same event seen twice, and so is the price.
            while (from < ticks.size() && ticks.get(from) + data.fairTicks() < tick) {
                int note = ticks.get(from);
                if (judged.add(note)) {
                    state.missed++;
                    state.combo = 0;
                    state.perfectStreak = 0;
                    state.energy = clampEnergy(state.energy + PvzceConstants.ENERGY_MISS);
                }
                from++;
            }
            state.next.put(key, from);
        }
        syncStatus(level, state);
        // The end of the track, and the mode's own way of winning: the song is the clock, so the
        // tick its last bar lands on is the tick the run is over - whatever is still walking is
        // swept, and the player who is still standing has survived it. Checked after the miss
        // sweep so the last window closes before the level does, and only once (a `tick` past the
        // end would otherwise sweep on every frame until the end packet lands).
        if (data.hasEnd() && !state.finished && tick >= data.endTick()) {
            state.finished = true;
            level.sweepLawn();
            level.declareVictory();
        }
    }

    /**
     * Takes the chart's anchor if it has none, and tells the client where it is.
     *
     * <p>Both halves are one method because they are one fact: the tick the chart counts from is
     * the server's, and the client is not allowed to have a different one (see {@link Status}).
     */
    private static void anchor(LevelServer level, State state) {
        if (state.startTick < 0) {
            state.startTick = level.tickCount();
        }
        if (state.startSynced) {
            return;
        }
        state.startSynced = true;
        level.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_RHYTHM, Status.CODEC,
                statusOf(state)));
    }

    /**
     * Re-sends the run's numbers on their cadence.
     *
     * <p>{@code level.tickCount() % STATUS_INTERVAL_TICKS} rather than a counter of this
     * mechanic's own: the level's tick is already in the save and already the clock the chart is
     * measured against, so the cadence survives a save and resume without a second field to keep
     * in step (the same reasoning {@code FogMechanic} uses).
     */
    private static void syncStatus(LevelServer level, State state) {
        if (level.tickCount() % STATUS_INTERVAL_TICKS != 0) {
            return;
        }
        level.send(MechanicSyncS2C.of(PvzceIds.MECHANIC_RHYTHM, Status.CODEC, statusOf(state)));
    }

    /** The run's numbers as the wire carries them: energy rounded, everything else as it is. */
    private static Status statusOf(State state) {
        return new Status(state.startTick, Math.round(state.energy), state.perfect, state.good,
                state.fair, state.missed, state.combo, state.bestCombo, state.perfectStreak,
                multiplierOf(state.energy), state.streakRewards);
    }

    /**
     * How many times a plant's volley is repeated right now: 1, 2 or 3.
     *
     * <p>What the bar buys. Read per level by {@code LevelServer.projectileCountMultiplier}, which
     * is the one place a plant can ask about it - so the answer is one function of one number
     * rather than a rule each capability re-implements.
     */
    public static int projectileCountMultiplier(LevelServer level) {
        State state = level.mechanicStateOrNull(PvzceIds.MECHANIC_RHYTHM, State.class);
        return state == null ? 1 : multiplierOf(state.energy);
    }

    private static int multiplierOf(float energy) {
        if (energy >= PvzceConstants.ENERGY_TRIPLE_AT) {
            return 3;
        }
        return energy >= PvzceConstants.ENERGY_DOUBLE_AT ? 2 : 1;
    }

    /** Keeps the bar inside its own ends; every change to it goes through here. */
    private static float clampEnergy(float energy) {
        return Math.max(0F, Math.min(PvzceConstants.ENERGY_MAX, energy));
    }

    /**
     * Fires the volleys the streak has earned, one per tick.
     *
     * <p>A jalapeno in every row of the board - "五行每行一个" - which between them burn the whole
     * lawn. Each row is the plant's own blast, read off the jalapeno's definition rather than
     * repeated here ({@code ExplosiveCapability.fireRow}): the damage, the damage type, whether it
     * melts the zamboni's ice and the sound are all the plant's, so a pack that retunes its
     * jalapeno retunes the reward too.
     *
     * <p>Released from {@code tick} and not from {@code judge}, which is where the streak is
     * actually counted. A press arrives while the server is handling packets, and
     * {@code LevelServer.emitEffect} is a no-op outside the level's own tick - the bridge that
     * carries effects and sounds to the clients only exists for the duration of {@code tick} - so
     * a row of fire lit at packet time would be silent and invisible. Damage and entity spawns
     * would survive it; the fire would not, and half a reward is worse than a late one.
     */
    private static void release(LevelServer level, State state) {
        while (state.pendingJalapenos > 0) {
            state.pendingJalapenos--;
            fireEveryRow(level);
        }
    }

    /** One volley: a jalapeno's blast in each row, the sound riding the first row only. */
    private static void fireEveryRow(LevelServer level) {
        com.pvzce.api.content.PlantDef jalapeno =
                com.pvzce.common.core.BuiltInRegistries.PLANTS.get(PvzceIds.JALAPENO);
        com.pvzce.common.capability.plant.ExplosiveCapability blast = jalapeno == null ? null
                : jalapeno.capability(com.pvzce.common.capability.plant.ExplosiveCapability.class)
                        .orElse(null);
        if (blast == null) {
            // No jalapeno in this pack: the streak is earned and cannot be paid. Not an error -
            // a total conversion may not ship the plant - so it is a quiet no-op rather than a
            // crash, and the counter still moves so the HUD is not lying about the run.
            return;
        }
        com.pvzce.server.Team team = level.team(level.humanTeamId());
        Identifier sound = jalapeno.sounds().explode().orElse(PvzceSounds.PLANT_JALAPENO);
        for (int row = 0; row < level.height(); row++) {
            com.pvzce.common.capability.plant.ExplosiveCapability.fireRow(level, row, blast.damage(),
                    blast.damageType(), blast.meltsIce(), row == 0 ? sound : null, team);
        }
    }

    /**
     * The streak length that pays next, given the one just paid.
     *
     * <p>Public because the rule is the mode's and a test can state it directly; the caller that
     * matters is {@link #judge}.
     *
     * <p>The four named milestones in order, and then every {@link
     * PvzceConstants#PERFECT_STREAK_STEP} past the last of them: a run that has already played a
     * flawless hundred notes keeps being paid for it rather than falling off a cliff at exactly
     * the point it became impressive.
     */
    public static int nextStreakReward(int paid) {
        for (int milestone : PvzceConstants.PERFECT_STREAK_MILESTONES) {
            if (milestone > paid) {
                return milestone;
            }
        }
        int last = PvzceConstants.PERFECT_STREAK_MILESTONES[
                PvzceConstants.PERFECT_STREAK_MILESTONES.length - 1];
        int steps = (paid - last) / PvzceConstants.PERFECT_STREAK_STEP + 1;
        return last + steps * PvzceConstants.PERFECT_STREAK_STEP;
    }

    /**
     * Counts one press, or refuses it.
     *
     * <p>The refusals are what make the packet safe to accept: an unknown lane, a note that does not
     * exist, a note that has already been counted, or a press more than the widest window away from
     * the note <em>by the server's own clock</em>. The last one is the one that matters - the client
     * says how well it did, and the server says whether it was anywhere near.
     *
     * <p>The verdict is the better of the two clocks: the client's own distance (it has the frame
     * the key arrived in, and that is what a player feels) and the server's (which knows how far
     * the note really was). A client that lies about the first still has to be inside the widest
     * window of a note that exists, and can only be paid for that note once.
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
        anchor(level, state);
        int tick = level.tickCount() - state.startTick;
        int delta = Math.abs(tick - noteTick);
        if (delta > data.fairTicks()) {
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
        RhythmChartData.Grade grade = data.gradeOf(Math.min(delta, Math.abs(perceivedTicks)));
        switch (grade) {
            case PERFECT -> state.perfect++;
            case GOOD -> state.good++;
            default -> state.fair++;
        }
        state.combo++;
        state.bestCombo = Math.max(state.bestCombo, state.combo);
        // What the note is worth, and what it does to the streak. The two are one switch because
        // they are one fact - how well the note was played - and a second switch over the same
        // grade is how a reward ends up disagreeing with the tally beside it.
        state.energy = clampEnergy(state.energy + energyOf(grade));
        if (grade == RhythmChartData.Grade.PERFECT) {
            state.perfectStreak++;
            // A streak that reaches the next milestone earns a volley. `while` rather than `if`
            // because the milestones past the last named one are twenty apart, so a single note
            // can only ever cross one - and the loop is what keeps that true if the table is ever
            // written with two entries a hair apart.
            while (state.perfectStreak >= state.nextStreakReward) {
                state.streakRewards++;
                state.pendingJalapenos++;
                state.nextStreakReward = nextStreakReward(state.nextStreakReward);
            }
        } else {
            // A GOOD keeps the combo and breaks the streak: "in a row" has to mean in a row, or
            // the number the reward is named after is not the number the player is watching.
            state.perfectStreak = 0;
        }
        int volleys = data.volleys(grade);
        if (grade == RhythmChartData.Grade.PERFECT) {
            paySun(level, data, lane);
        }
        attack(level, data, lane, volleys);
        return true;
    }

    /** What one verdict pays the energy bar. */
    private static int energyOf(RhythmChartData.Grade grade) {
        return switch (grade) {
            case PERFECT -> PvzceConstants.ENERGY_PERFECT;
            case GOOD -> PvzceConstants.ENERGY_GOOD;
            case FAIR -> PvzceConstants.ENERGY_FAIR;
            case MISS -> PvzceConstants.ENERGY_MISS;
        };
    }

    /**
     * Drops the PERFECT note's sun out of a plant in the lane it was played in.
     *
     * <p>An entity rather than a number added to the bank: the sun pops out of the plant the note
     * just fired, and the player's own collection rules take it from there - a level with
     * {@code pvzce:auto_collect} picks it up a quarter of a second later, one without it makes the
     * player click (see {@code LevelServer.tickAutoCollect}). The mode therefore pays in the game's
     * own currency, which is what makes "a perfect note is worth a sun" a sentence about the game
     * rather than about a counter.
     *
     * <p>A lane with nothing planted in it pays nothing. That is not a punishment invented here:
     * the sun comes <em>out of a plant</em>, and the same note in a planted column is worth it.
     */
    private static void paySun(LevelServer level, RhythmChartData data, RhythmChartData.Lane lane) {
        if (data.perfectSun() <= 0) {
            return;
        }
        java.util.List<com.pvzce.server.entity.PlantEntity> plants = plantsOf(level, lane);
        if (plants.isEmpty()) {
            return;
        }
        com.pvzce.server.entity.PlantEntity plant =
                plants.get(level.random().nextInt(plants.size()));
        level.spawnProducedResource(PvzceIds.SUN, data.perfectSun(),
                plant.cellX(), plant.cellY(), level.team(level.humanTeamId()));
    }

    /**
     * The plants one lane is made of: a column's, or a row's.
     *
     * <p>Every plant in the cell and not just the top one: a lily pad under a peashooter is two
     * plants, both of them in the lane the player played, and a note that only ordered the one on
     * top would be a rule nobody could see.
     */
    private static java.util.List<com.pvzce.server.entity.PlantEntity> plantsOf(
            LevelServer level, RhythmChartData.Lane lane) {
        java.util.List<com.pvzce.server.entity.PlantEntity> plants = new java.util.ArrayList<>();
        if (lane.kind() == RhythmChartData.LaneKind.COL) {
            for (int row = 0; row < level.height(); row++) {
                plants.addAll(level.plantsAt(lane.index(), row));
            }
        } else {
            for (int column = 0; column < level.width(); column++) {
                plants.addAll(level.plantsAt(column, lane.index()));
            }
        }
        return plants;
    }

    /**
     * The lane's own attack: every plant in it attacks this many times.
     *
     * <p>This is the mode's whole output, and it is deliberately the plants' own attacks rather
     * than a shape drawn on the lawn: a peashooter fires a pea down its row, a chomper bites what
     * is in front of it, and a wall-nut does nothing at all - which is what the player's choice of
     * plants is <em>for</em>. The count is the verdict's (see {@code RhythmChartData#volleys}):
     * three volleys for a PERFECT, two for a GOOD, one for a FAIR, none for a MISS.
     *
     * <p>The queue lives on the plant (see {@code PlantEntity#queueStrikes}) so the volleys come
     * out over a few ticks instead of on top of each other. A level whose plants hold their fire
     * has no other way for them to act; a hand-written chart that opted out gets these on top of
     * whatever the plants were already doing, which is the honest reading of "the lane attacks".
     */
    private static void attack(LevelServer level, RhythmChartData data, RhythmChartData.Lane lane,
                               int volleys) {
        if (volleys <= 0) {
            return;
        }
        for (com.pvzce.server.entity.PlantEntity plant : plantsOf(level, lane)) {
            plant.queueStrikes(volleys);
        }
    }

    /** One lane's note ticks as a set, computed once per lane and kept in the run's state. */
    private static java.util.Set<Integer> notesOf(State state, RhythmChartData data,
                                                  RhythmChartData.Lane lane) {
        String key = laneKey(lane.kind(), lane.index());
        return state.notes.computeIfAbsent(key,
                ignored -> new java.util.HashSet<>(data.ticksOf(lane)));
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
    public record Score(int perfect, int good, int fair, int missed, int combo, int bestCombo,
                        int perfectStreak, int energy, int jalapenos) {
    }

    public static Score score(LevelServer level) {
        State state = level.mechanicStateOrNull(PvzceIds.MECHANIC_RHYTHM, State.class);
        if (state == null) {
            return new Score(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
        return new Score(state.perfect, state.good, state.fair, state.missed, state.combo,
                state.bestCombo, state.perfectStreak, Math.round(state.energy), state.streakRewards);
    }

    @Override
    public List<String> validate(LevelDef def, RhythmChartData data) {
        List<String> errors = new ArrayList<>(data.validate());
        // One level, one way of playing it. A chart with rows in it answers to D F G H J and one
        // with columns answers to the row of letters above them; a chart with both is a chart
        // whose keys the player has to work out per note, and the on-lawn key hints (see
        // `InGameScreen`) can only ever be in one of the two places. This started as a report
        // about the shipped levels being ambiguous, so it is a data error rather than a style
        // note: the four tiers are one kind each.
        RhythmChartData.LaneKind only = null;
        for (RhythmChartData.Lane lane : data.lanes()) {
            if (only == null) {
                only = lane.kind();
            } else if (only != lane.kind()) {
                errors.add("rhythm chart mixes row and column lanes: a level plays one way or the"
                        + " other, and the players' key hints follow the lanes");
                break;
            }
            int limit = lane.kind() == RhythmChartData.LaneKind.COL ? def.width() : def.height();
            if (lane.index() >= limit) {
                errors.add("rhythm lane " + lane.kind().json() + " " + lane.index()
                        + " is off a " + def.width() + "x" + def.height() + " board");
            }
        }
        // A note after the end would be a note nobody can play: the end of the chart is where the
        // lawn is swept and the level stops ticking.
        if (data.hasEnd()) {
            for (RhythmChartData.Lane lane : data.lanes()) {
                if (data.ticksOf(lane).stream().anyMatch(tick -> tick > data.endTick())) {
                    errors.add("rhythm chart ends at beat " + data.endBeat()
                            + " but has notes after it, which nobody could reach");
                    break;
                }
            }
        }
        if (def.initialSun() < 0) {
            errors.add("rhythm chart on a level with negative sun");
        }
        // A hold-fire chart whose notes are worth no attacks is a level where the keyboard does
        // nothing: the plants never act on their own and the notes do not tell them to, so the
        // lawn can only be lost. Data error rather than an odd tuning.
        if (data.plantsHoldFire() && data.attackVolleys() <= 0) {
            errors.add("rhythm chart holds the plants' fire but its notes are worth no attacks:"
                    + " nothing on the lawn could ever act");
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
        root.putInt("RhythmFair", score.fair());
        root.putInt("RhythmMissed", score.missed());
        root.putInt("RhythmBestCombo", score.bestCombo());
        root.putInt("RhythmStart", state(level).startTick);
        // The bar and the streak, as the floats and counters they are rather than as the rounded
        // numbers the HUD shows: a resumed run has to come back to the same multiplier, and
        // rounding 2999.9 to 3000 on the way through a save would hand out firepower the player
        // had not earned.
        root.putFloat("RhythmEnergy", state(level).energy);
        root.putInt("RhythmStreak", state(level).perfectStreak);
        root.putInt("RhythmNextReward", state(level).nextStreakReward);
        root.putInt("RhythmRewards", state(level).streakRewards);
    }

    @Override
    public void applySave(LevelServer level, RhythmChartData data, CompoundTag root) {
        if (!root.contains("RhythmPerfect")) {
            return;
        }
        State state = state(level);
        state.perfect = Math.max(0, root.getInt("RhythmPerfect"));
        state.good = Math.max(0, root.getInt("RhythmGood"));
        // A save written before the third window existed has no fair count, and zero is the
        // honest answer for one: the notes it counted were counted as perfect or good at the time.
        state.fair = Math.max(0, root.getInt("RhythmFair"));
        state.missed = Math.max(0, root.getInt("RhythmMissed"));
        state.bestCombo = Math.max(0, root.getInt("RhythmBestCombo"));
        state.startTick = root.getInt("RhythmStart");
        // Same story for the bar and the streak, which postdate the first four saves in this
        // format: absent means zero - an empty bar and no streak - which is where every run starts.
        state.energy = clampEnergy(root.getFloat("RhythmEnergy"));
        state.perfectStreak = Math.max(0, root.getInt("RhythmStreak"));
        state.streakRewards = Math.max(0, root.getInt("RhythmRewards"));
        // And the next milestone is recomputed from the streak when the save has none: a run
        // resumed from a save written before the rewards existed must not be handed its first
        // jalapeno by a default of 30 that its streak has already passed.
        int saved = root.getInt("RhythmNextReward");
        state.nextStreakReward = saved > state.perfectStreak
                ? saved
                : nextStreakReward(state.perfectStreak);
        // A resumed run's client has just started and knows nothing about the chart: the anchor is
        // sent again rather than kept, or the player would be playing against a chart that had not
        // begun as far as the screen was concerned (see `Status`).
        state.startSynced = false;
    }
}
