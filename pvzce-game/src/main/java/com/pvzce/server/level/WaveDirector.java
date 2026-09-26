package com.pvzce.server.level;

import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.WavePacingData;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.capability.zombie.BobsledCapability;
import com.pvzce.common.level.mechanic.BudgetPlanner;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.nbt.FloatTag;
import com.pvzce.common.nbt.IntTag;
import com.pvzce.common.nbt.ListTag;
import com.pvzce.common.nbt.StringTag;
import com.pvzce.common.nbt.Tag;
import com.pvzce.common.network.packet.WaveProgressS2C;
import com.pvzce.server.entity.ZombieEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The wave clock: when a wave arrives, how fast it releases, and what the HUD is told.
 *
 * <p>Extracted from {@code LevelServer}, where these fifteen fields and eleven methods were woven
 * through the tick, the save file and the win check. The pacing rules are the subtlest thing in
 * the level simulation - a wave's delay counts from the previous wave <em>finishing</em>, an
 * opening wave paces itself by the player's kills, and the last wave's delay is scaled by the
 * level's interval curve - and they were sitting in the middle of two thousand lines of unrelated
 * code.
 *
 * <p><b>Rounds.</b> What the director plays is a sequence of rounds, each of them a fixed number
 * of waves, and the wave it is about to send is asked for one at a time rather than read from a
 * list. On an ordinary level a round is the whole table and there is only ever one of them; on an
 * endless level a round is ten to thirty waves and the next one is heavier. The director does not
 * know which of the two it is driving - it knows the round number, the wave inside it, and who to
 * ask (see {@link Host#waveAt}), which is what keeps the endless generator out of the clock.
 *
 * <p>What it needs from the level is small and one-directional, so it asks through {@link Host}:
 * the board's size, the level's random source, the two sounds a wave can make, the scene
 * operation the final wave performs (opening graves), and the wave source. Nothing here touches
 * entities, scene or save format directly except its own block.
 *
 * <p>The save block keeps the key names it had when it lived in {@code LevelServer.save}: a save
 * written by an earlier build of this branch is not a compatibility promise (the project is
 * unpublished), but the tests that pin the round trip are, and the keys are what they read.
 */
public final class WaveDirector {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Waves");

    /**
     * The format of this director's save block.
     *
     * <p>2 because the position inside a run is now "a round and a wave in it" rather than "the
     * nth wave of one list". A block without this key was written by the version whose endless
     * levels expanded a four-thousand-entry table, and its {@code NextWaveIndex} means nothing
     * to a generator - so a level that reads one refuses it (see {@link #restore}) and the
     * caller drops the save rather than resuming into a different run.
     */
    public static final int SAVE_VERSION = 2;

    /**
     * What the health drain may shorten a countdown to: the original's 200 ticks of "a wave".
     *
     * <p>See {@link #healthDrainApplies}: this is the pace the original switches to the moment the
     * wave on the lawn is beaten, and it is what makes a level read as continuous rather than as a
     * sequence of waits.
     */
    public static final int HEALTH_DRAIN_TICKS = 200;
    /** How much of a wave's health may be gone before the original drops the countdown to 200. */
    public static final float HEALTH_DRAIN_FLOOR_RATIO = 0.5F;
    public static final float HEALTH_DRAIN_CEILING_RATIO = 0.65F;

    /** Everything a wave needs from the level it runs in. */
    public interface Host {
        int width();

        int height();

        java.util.Random random();

        /**
         * True when any cell of this row is water.
         *
         * <p>Asked by the lane chooser, which keeps walkers out of the pool. It is on the host
         * rather than derived here because "which rows are water" is a fact about the level's
         * scene, and the scene is not fixed for the life of a level - a flood mutation rewrites it.
         */
        boolean rowIsWater(int row);

        /**
         * True when any cell of this row is the zamboni's ice.
         *
         * <p>The lane chooser's second question, and the same kind of fact as the first: a sled
         * needs a frozen lane the way a walker needs a dry one, and "which rows are iced" is a
         * fact about the level's scene that the director cannot see for itself. A host with no
         * ice answers {@code false} for every row, which is what a lawn level means.
         */
        default boolean rowHasIce(int row) {
            return false;
        }

        /**
         * The lane the very first zombie of the run must arrive in, or {@code -1} for "deal as
         * usual".
         *
         * <p>Asked once per run, for one spawn. It exists for the rake: the original guarantees
         * that the rake's lane is the lane the first zombie walks down, because a rake the first
         * zombie never reaches is a purchase that silently did nothing. The level answers with its
         * rake's lane while that rake is still lying there and {@code -1} otherwise, so a level
         * with no rake - or a run resumed after one sprang - deals lanes exactly as before.
         *
         * <p>{@code zombieId} is passed so the host can decline when the lane would be wrong for
         * this particular zombie, rather than the director having to know what a rake is.
         */
        default int forcedOpeningLane(Identifier zombieId) {
            return -1;
        }

        /**
         * The wave this round holds at this index, generated or read from the level's table.
         *
         * <p>The one thing the director cannot know for itself: a level with a wave table
         * answers from the list, and an endless level answers from its schedule. Both are pure
         * functions of {@code (round, index)}, which is why a save can hold those two numbers
         * instead of a table.
         *
         * @return the wave, or {@code null} when this position does not exist
         */
        WaveDef waveAt(int round, int index);

        /** How many waves the round holds; zero means the round has none. */
        int wavesInRound(int round);

        /** How many zombies of this one {@code healthScale} times its own health; 1 for ordinary. */
        ZombieEntity spawnZombie(Identifier zombieId, float x, int row, float healthScale);

        /**
         * The total health still standing of one wave's own zombies, armour included, or
         * {@code -1} when this level cannot total it.
         *
         * <p>What the health drain reads (see {@link WaveDirector#healthDrainApplies}); the
         * host answers it by summing the entities it owns. {@code -1} switches the drain off,
         * which is the right answer for a harness that has no entities to weigh.
         */
        default int waveHealth(int waveKey) {
            return -1;
        }

        /** Zombies still standing, corpses excluded. */
        long aliveZombieCount();

        /** Every entity on the board, for the two questions that need to weigh them. */
        List<com.pvzce.server.entity.PvzceEntity> entities();

        /**
         * The level's living zombies, oldest first, for the resume-time re-owning of a wave.
         *
         * <p>Only {@link WaveDirector#reownRestoredWaves} asks this: a stockpile wave counts its
         * own zombies, and a restored process has entity ids that mean nothing. A host that does
         * not answer it costs a resumed stockpile wave one refill.
         */
        default List<Integer> livingZombieIds() {
            return List.of();
        }

        /** True while the entity with this id is a living zombie; corpses answer false. */
        boolean zombieAlive(int entityId);

        /** Moves everything spawned this tick into the level's live entity list. */
        void flushPending();

        void emitEffect(String particle, float x, float y, Identifier sound, float volume, float pitch);

        /** The final wave's farewell: every grave gives up one of this wave's zombies. */
        void riseGraveZombies(WaveDef wave);

        /**
         * How much faster than written this level's waves run; {@code 1} = exactly as written.
         *
         * <p>The level's {@code zombie_spawn_speed_multiplier} rule. Read at the two places a
         * time is taken from the wave table (the gap between waves and the gap between the
         * zombies inside one), so a level that speeds its spawns up cannot speed up one of them
         * and forget the other.
         */
        float zombieSpawnSpeedMultiplier();
    }

    private final Host host;
    /**
     * How this level's waves behave beyond the wave table; see {@link WavePacingData}.
     *
     * <p>Every level has one - a level that declares no {@code pvzce:wave_pacing} runs on the
     * engine's default, which {@code LevelMechanics.effective} injects. The clear bonus, the
     * death gate's tightening and the per-wave mode table all read it, each behind its own
     * switch, so a level that wants none of them writes the neutral value.
     */
    private final WavePacingData pacing;

    /** True when the level's waves are generated: there is always a next round, so no win. */
    private final boolean endless;

    private final List<PendingWaveSpawn> pendingWaveSpawns = new ArrayList<>();
    /** The round being played, one-based. */
    private int round = 1;
    /** The wave inside {@link #round} that comes next, zero-based. */
    private int waveIndex;
    /** How many waves {@link #round} holds, read once when the round began. */
    private int roundWaves;
    private int waveIntervalTicks;
    /**
     * The delay the next wave is actually counting down, which the arrival is tested against.
     *
     * <p>Written on the first tick of a countdown and frozen for the rest of it: the clear bonus
     * may shorten the wait, and the counter is clamped to this number - so a target that moved
     * after the counter had passed it would leave the wave waiting for a value it can never
     * reach, which is a level that stops after one wave.
     */
    private int nextWaveTargetTicks;
    /** The delay as the level wrote it; {@link #nextWaveTargetTicks} is derived from this. */
    private int nextWaveDelayTicks;
    private float waveProgress;
    private boolean waveWarningActive;
    private boolean waveWarningFinal;
    private boolean openingGateArmed;
    private int openingGateTicks;
    private int openingGateHoldTicks;
    private boolean waveArrivalHeld;
    /**
     * True once the run's first zombie has been dealt a lane.
     *
     * <p>The one spawn the host gets a say in ({@link Host#forcedOpeningLane}): the rake's lane is
     * the lane the first zombie walks down, and "first" is counted here rather than by the host
     * because the host cannot tell a fresh run from a resumed one. In memory only, like the rest of
     * the director's clocks - a run resumed before its first zombie simply offers the lane again.
     */
    private boolean firstSpawnDealt;
    /**
     * True from the moment the round's last wave is fully released until the level acknowledges
     * it: the run is between rounds, and no wave is owed until the player has picked their cards.
     *
     * <p>Only an endless level ever sets this. A level with one round never rolls over, so the
     * flag stays false and the "all waves released" path means what it always did.
     */
    private boolean roundClearPending;
    /** Set when the HUD-visible part of the wave state changed and the client has not heard. */
    private boolean dirty = true;
    private final Set<Integer> announcedWaves = new HashSet<>();
    private final Set<Integer> announcedWarnings = new HashSet<>();

    /**
     * Which wave owns each zombie still on the lawn, keyed by {@link #waveKey}.
     *
     * <p>The stockpile cap and the survival-ratio gate both ask "how many of <em>this</em> wave
     * are still standing", and the level's own {@code aliveZombieCount()} answers a different
     * question - the whole lawn. A stockpile of two must not be satisfied by the previous wave's
     * survivors, and a wave that waits to be finished must not be kept waiting by the next one's.
     *
     * <p>Not saved: entity ids do not survive a process, exactly like the death gate's own handle
     * (see {@code PendingWaveSpawn.gateZombieId}). A restored level re-owns what it can - see
     * {@link #restore}.
     */
    private final Map<Integer, Integer> waveOwner = new HashMap<>();
    /** How many zombies of each wave are alive, kept in step with {@link #waveOwner}. */
    private final Map<Integer, Integer> aliveByWave = new HashMap<>();
    /** How many of each wave's zombies have died. */
    private final Map<Integer, Integer> killsByWave = new HashMap<>();
    /** How many zombies each wave set out to send, for the kill share. */
    private final Map<Integer, Integer> arcByWave = new HashMap<>();
    /** The health each wave has put on the lawn so far, for the health drain. */
    private final Map<Integer, Integer> healthOnLawnByWave = new HashMap<>();
    /**
     * The health the wave after this one waits for: the original's {@code mZombieHealthToNextWave}.
     *
     * <p>Rolled once per countdown - see {@link #rollHealthDrainTarget} - and negative while it has
     * not been rolled yet, which is the state a countdown starts in. A line that moved as the wave
     * took damage would not be the same mechanic, and one rolled from a wave that had not finished
     * arriving would be a line of zero.
     */
    private int healthDrainTarget = -1;
    /**
     * Waves whose release queue is gone but whose deaths still matter.
     *
     * <p>A queue is removed the moment its last zombie is out, and that is exactly when the
     * survival-ratio gate starts caring, so "is this wave released" has to outlive the queue.
     */
    private final Set<Integer> releasedWaves = new HashSet<>();

    /**
     * @param configured         the level's own table; empty on an endless level
     * @param intervalEndMultiplier the level's {@code wave_interval_end_multiplier}
     * @param pacing             the level's effective pacing
     * @param endless            true when the level generates its waves and never finishes
     */
    public WaveDirector(Host host, List<WaveDef> configured, float intervalEndMultiplier,
                        WavePacingData pacing, boolean endless) {
        this.host = host;
        this.pacing = pacing == null ? WavePacingData.DEFAULT : pacing;
        this.intervalEndMultiplier = intervalEndMultiplier;
        this.endless = endless;
        this.table = normalizeWaves(configured);
        this.roundWaves = host.wavesInRound(1);
        this.waveIndex = 0;
        this.nextWaveDelayTicks = firstWaveDelay();
    }

    /** The level's own table; empty on a level that generates its waves. */
    private final List<WaveDef> table;

    /** The pacing this level runs on, for the tests and for the level's own reporting. */
    public WavePacingData pacing() {
        return pacing;
    }

    private final float intervalEndMultiplier;

    /**
     * Cross-field normalization: a final wave may only be the last one, and the
     * last wave is always treated as final even when the data omits the type.
     *
     * <p>Applied to a level's own table only. A generated wave is never retyped: an endless
     * round's last wave is a huge one, and the final-wave branch is what plays the siren and
     * opens every grave, which is a level ending rather than a round ending. A round that ended
     * that way would open the graves ten times a run.
     */
    private static List<WaveDef> normalizeWaves(List<WaveDef> configured) {
        if (configured.isEmpty()) {
            return List.of();
        }
        List<WaveDef> normalized = new ArrayList<>(configured.size());
        int last = configured.size() - 1;
        for (int i = 0; i <= last; i++) {
            WaveDef wave = configured.get(i);
            WaveDef.WaveType type = wave.type();
            if (i < last && type == WaveDef.WaveType.FINAL) {
                LOGGER.warn("Wave {} is marked final but is not last; treating it as huge.", i + 1);
                type = WaveDef.WaveType.HUGE;
            } else if (i == last && type != WaveDef.WaveType.FINAL) {
                type = WaveDef.WaveType.FINAL;
            }
            normalized.add(type == wave.type() ? wave : wave.asType(type));
        }
        return List.copyOf(normalized);
    }

    /** The wave at a position, from the level's table or from its generator. */
    private WaveDef waveAt(int round, int index) {
        return host.waveAt(round, index);
    }

    /**
     * The delay of the first wave of the current round, before any countdown has run.
     *
     * <p>Read at construction and again whenever a round begins, so a resumed level and a fresh
     * round both start their meter from the wave they are actually about to send.
     */
    private int firstWaveDelay() {
        return nextWaveDelayTicks(0);
    }

    /** The written delay of the wave at an index of the current round, or -1 past its end. */
    private int nextWaveDelayTicks(int index) {
        WaveDef wave = index < roundWaves ? waveAt(round, index) : null;
        return wave == null ? -1 : effectiveWaveDelay(wave, index, roundWaves);
    }

    /**
     * Effective delay of a wave after applying the level's linear interval curve.
     * The first wave uses the configured delay as-is; the last wave uses
     * {@code wave_interval_end_multiplier}.
     */
    private int effectiveWaveDelay(WaveDef wave, int waveIndex, int total) {
        float endMultiplier = Float.isFinite(intervalEndMultiplier) ? intervalEndMultiplier : 1F;
        endMultiplier = Math.max(0.05F, Math.min(10F, endMultiplier));
        // The curve runs across the round rather than across the level, because on an endless
        // level there is no "end of the level" to scale towards. An ordinary level has one round,
        // so the two are the same thing there.
        float progress = total <= 1 ? 0F : waveIndex / (float) (total - 1);
        float multiplier = 1F + (endMultiplier - 1F) * progress;
        return Math.max(1, Math.round(wave.delay() * multiplier / spawnSpeed()));
    }

    /**
     * The level's spawn speed, as a positive factor to divide times by.
     *
     * <p>One read for both times taken from the wave table (see the host method), and defensive
     * about the value even though the rule itself is clamped on the way in: a zero or a NaN here
     * would make every wave arrive on tick one.
     */
    private float spawnSpeed() {
        float speed = host.zombieSpawnSpeedMultiplier();
        return Float.isFinite(speed) && speed > 0F ? speed : 1F;
    }

    /** A per-zombie interval at this level's spawn speed, never below the wave's own floor. */
    private int effectiveSpawnInterval(WaveDef wave) {
        // WaveDef.spawnInterval() already floors the authored number at 15 ticks; the floor is
        // about "a wave is not one simultaneous dump", so it holds after scaling too.
        return Math.max(15, Math.round(wave.spawnInterval() / spawnSpeed()));
    }

    /** One tick of the wave clock. Call it once per level tick, before the entities move. */
    public void tick() {
        if (roundClearPending) {
            // Between rounds: everything is out and the lawn is clear, and the next wave is
            // whatever the player picks their cards for. Ticking the clock here would send the
            // next round's first wave into a screen nobody can see.
            spawnPendingWaveZombies();
            return;
        }
        if (waveIndex < roundWaves) {
            // A wave's delay counts from the tick the previous wave *triggered*, and the
            // counter is only frozen while that wave is still putting its zombies out. Both
            // halves are load-bearing:
            //
            // * From the trigger, because that is the moment every shipped wave table's delay
            //   is written against (see `docs/架构-服务端.md` §4.7.2): the original resets its
            //   countdown the tick a wave spawns, so its release window runs *inside* the gap.
            //   Arming the countdown after the release instead charged every wave the release
            //   time a second time - and a level's 20-30 zombie waves release for four to six
            //   seconds each, which is where "waiting forever between waves" came from.
            // * Frozen while it releases, because counting through the release let two waves'
            //   queues run at once, and zombies from different waves then arrived a few seconds
            //   apart instead of on the pacing their own wave asked for - the "they all come
            //   out together" of a level whose first waves are authored ten seconds apart.
            //
            // What that leaves is `release window + max(delay - release window, 0)`: never an
            // overlap, and never a wait longer than the author wrote plus the time their own
            // wave takes to walk in.
            //
            // The counter is frozen rather than held at its target so the warning window
            // stays a property of the gap the author wrote: the warning shows for
            // `warning_ticks` before the wave arrives, and the arrival is this countdown
            // reaching its target.
            boolean firstWave = waveIndex == 0;
            if (firstWave || !currentWaveStillReleasing()) {
                // Three things can make this countdown shorter than written, and all three are the
                // answer to "I killed everything and the level is making me stand here": the wave
                // on the lawn being beaten (the health drain, see `healthDrainApplies`), a cleared
                // lawn (see `clearRewardApplies`) and a wave the player has already answered (the
                // survival-ratio mode, see `earnedEarlyArrival`). The target is decided on the
                // countdown's first tick and then frozen, both because these are rewards for the
                // state the player reached and because a finish line that moved under a running
                // countdown would stop the level dead.
                if (waveIntervalTicks == 0) {
                    nextWaveTargetTicks = clearRewardApplies()
                            ? pacing.clearRewardDelay(nextWaveDelayTicks)
                            : nextWaveDelayTicks;
                    rollHealthDrainTarget();
                }
                waveIntervalTicks = Math.min(waveIntervalTicks + delayStep(), nextWaveTargetTicks);
                // The drain is tested every tick, not only on the first one: the original does the
                // same, and "the player finished the wave off halfway through the wait" is the
                // common case. It is a *ceiling on what is left* rather than a new finish line each
                // tick - this counter counts up and the original's counts down, so the original's
                // "200 ticks left to run" is "where this counter is, plus 200", and it only ever
                // moves the target backwards. Re-arming it every tick instead pushed the arrival
                // 200 ticks further away on every tick the wave stayed beaten, which is a countdown
                // that never arrives. A countdown still frozen because its wave is coming out is
                // skipped: that wave has not finished arriving, so there is nothing beaten yet.
                if (healthDrainApplies()) {
                    nextWaveTargetTicks = Math.min(nextWaveTargetTicks,
                            waveIntervalTicks + HEALTH_DRAIN_TICKS);
                }
            }
            // Due, and then held only if an opening wave is still on the field: the clock runs
            // while the player fights, so clearing the field early never costs the gap the
            // level asked for - it only ever costs the *arrival* of a wave that is already due.
            //
            // `waveIntervalTicks > 0` is what says a countdown has actually run: the counter sits
            // at zero on the tick a wave triggers - and stays there for as long as that wave is
            // still putting its zombies out - so a "due" test that read that zero as "the wait is
            // over" arrived every following wave on the tick the one before it was triggered.
            boolean due = waveIntervalTicks > 0 && waveIntervalTicks >= nextWaveTargetTicks;
            waveArrivalHeld = due && openingWaveStillOnTheField();
            if (due && !waveArrivalHeld) {
                WaveDef wave = waveAt(round, waveIndex);
                if (wave != null) {
                    triggerWave(wave, waveIndex);
                } else {
                    // A generator with nothing to send: the round is over rather than the level
                    // being stuck, so the same "wait for the next round" state applies.
                    finishRound();
                }
            } else {
                updateWaveWarning();
            }
        } else {
            // The round's last wave is out: on an ordinary level that is the end of the level
            // and the win check takes it from here, and on an endless one it is the end of a
            // round, which the player closes by choosing their next cards.
            waveProgress = 1F;
            waveWarningActive = false;
            waveWarningFinal = false;
            waveArrivalHeld = false;
            if (endless) {
                finishRound();
            }
        }
        spawnPendingWaveZombies();
    }

    /**
     * Marks the round as waiting to be closed, once the lawn really is clear.
     *
     * <p>The zombies matter as much as the waves: a round that ended the moment its last wave
     * was <em>released</em> would stop the clock with the field still full, and - because the
     * lawn is kept between rounds - hand the player a card chooser in the middle of a fight.
     */
    private void finishRound() {
        if (roundClearPending || !pendingWaveSpawns.isEmpty()) {
            return;
        }
        if (host.aliveZombieCount() > 0) {
            return;
        }
        roundClearPending = true;
        dirty = true;
    }

    /**
     * Records that one of the wave on the lawn just died.
     *
     * <p>Called from the level's kill hook, and the bookkeeping the two wave-owned modes read:
     * the stockpile cap counts what is left of a wave, and the survival-ratio gate counts what is
     * gone. A zombie that leaves the lawn any other way - reaching the house, a mower - is not a
     * kill and is not counted as progress; the level's own alive count is what notices it.
     */
    public void zombieDied(int entityId) {
        Integer key = waveOwner.remove(entityId);
        if (key == null) {
            return;
        }
        aliveByWave.merge(key, -1, Integer::sum);
        killsByWave.merge(key, 1, Integer::sum);
    }

    /**
     * How much of the next wave's countdown this tick is worth.
     *
     * <p>One, unless the player has earned more: a cleared lawn runs the countdown at
     * {@code clear_reward_factor} times the written rate, and a wave that has already been
     * answered (survival-ratio mode) adds one more tick of progress per tick. Additive rather
     * than multiplicative so the two cannot compound into "every wave arrives instantly".
     */
    private int delayStep() {
        boolean clear = pacing.clearRewardFactor() > 1F && clearRewardApplies();
        float step = clear ? pacing.clearRewardFactor() : 1F;
        if (pacing.earlyAdvance() && earnedEarlyArrival()) {
            step += 1F;
        }
        return Math.max(1, Math.round(step));
    }

    /**
     * True when the player has earned the clear bonus: nothing left to fight, nothing still due.
     *
     * <p>Both halves matter: no zombie standing <em>and</em> no queue still holding one. A wave
     * whose last zombie has not been released yet is a wave the player is still in the middle of
     * - shortening the next wave's countdown there would have two waves arriving together, which
     * is what the delay is for.
     */
    private boolean clearRewardApplies() {
        if (releasedWaves.isEmpty()) {
            return false;
        }
        if (aliveOfWave(waveKey(round, waveIndex - 1)) > 0) {
            return false;
        }
        return host.aliveZombieCount() == 0;
    }

    /**
     * True when the wave on the lawn is beaten badly enough that the next one comes now.
     *
     * <p>The original's own pacing, and the piece this engine was missing: {@code Board} rolls
     * {@code mZombieHealthToNextWave = RandRangeFloat(0.5, 0.65) * TotalZombiesHealthInWave()}
     * when a wave spawns and then drops its countdown straight to 200 ticks the moment the wave
     * still standing is at or below that line. On a level's 2500-3100 tick gaps that is the whole
     * difference between "a level that flows" and "a level that keeps making you stand on an empty
     * lawn": the shipped tables' delays are the original's numbers, and they were never meant to
     * be served in full by a player who is winning.
     *
     * <p>Two conditions, both the original's:
     *
     * <ul>
     *   <li>the countdown has more than {@value #HEALTH_DRAIN_TICKS} left to run - there has to be
     *       a wait to shorten. The original states the same thing as "the counter is above 200",
     *       and the "this wave has been going for 400 ticks" half of its guard is a different clock
     *       than this one: this countdown has already been frozen through the whole release
     *       window, which is the same "the player has been at this a while".</li>
     *   <li>the wave that triggered the countdown is at or below its rolled line. Measured from
     *       the wave that <em>arrived</em>, not from the whole lawn: a stockpile wave refilling
     *       from behind must not hold the next wave back, and the original asks
     *       {@code TotalZombiesHealthInWave(mCurrentWave - 1)} for exactly that reason. The line
     *       itself is a share of what that wave has <em>put out</em>, which is the other thing the
     *       original does - a wave still trickling has not offered the player its whole health yet.</li>
     * </ul>
     *
     * <p>A host that cannot total a wave's health ({@code waveHealth} answers {@code -1}) opts out.
     * So does a level whose wave arrived with no health at all - a shell wave on a level whose
     * zombies come from graves has nothing to drain, and its graves' own clock is the pacing.
     */
    private boolean healthDrainApplies() {
        if (!pacing.healthDrain()) {
            return false;
        }
        if (nextWaveTargetTicks <= HEALTH_DRAIN_TICKS || healthDrainTarget < 0) {
            return false;
        }
        if (waveIndex <= 0 || waveIndex > roundWaves) {
            return false;
        }
        int key = waveKey(round, waveIndex - 1);
        int sent = healthOnLawnByWave.getOrDefault(key, 0);
        if (sent <= 0) {
            return false;
        }
        int standing = host.waveHealth(key);
        return standing >= 0 && standing <= healthDrainTarget;
    }

    /**
     * Rolls this countdown's line from what the wave before it has put on the lawn, once.
     *
     * <p>Rolled on the countdown's first running tick rather than at the wave's trigger, and that
     * is not a detail: a wave triggers on the tick its predecessor's queue empties, and the
     * predecessor's last zombie may be published <em>later in that same tick</em> - so a line
     * snapped at the trigger would be a share of nothing and the drain would never open. The total
     * it reads is frozen by then (only a queue still trickling adds to it, and a countdown does not
     * run while one does), which is what makes "once" the same answer as the original's "once".
     *
     * <p>The original rolls {@code RandRangeFloat(0.5, 0.65) * TotalZombiesHealthInWave()}, and it
     * reads that total the tick a wave spawns; this reads the same number one tick later, when the
     * wave that arrived has stopped adding to it.
     */
    private void rollHealthDrainTarget() {
        int key = waveIndex <= 0 ? -1 : waveKey(round, waveIndex - 1);
        int sent = key < 0 ? 0 : healthOnLawnByWave.getOrDefault(key, 0);
        healthDrainTarget = sent <= 0 ? 0 : Math.round(sent
                * (HEALTH_DRAIN_FLOOR_RATIO + host.random().nextFloat()
                        * (HEALTH_DRAIN_CEILING_RATIO - HEALTH_DRAIN_FLOOR_RATIO)));
    }

    /**
     * True once the wave that has already arrived is mostly dead.
     *
     * <p>The survival-ratio gate, and only that mode: a wave has to have been authored as "come
     * back to me when this one is answered". The level-wide {@code early_wave_kill_ratio} is the
     * default that mode reads, not a switch every level gets - applying it to a fixed wave would
     * quietly rewrite wave tables nobody asked about.
     *
     * <p>A wave that is still pouring is not "mostly dead" however many of its zombies have died,
     * and the share is measured against what the wave <em>released</em> rather than against the
     * arc it started with: a twenty-zombie wave that has let out four and lost four of them has
     * not been answered.
     */
    private boolean earnedEarlyArrival() {
        if (waveIndex <= 0 || waveIndex >= roundWaves) {
            return false;
        }
        int key = waveKey(round, waveIndex - 1);
        if (!releasedWaves.contains(key)) {
            return false;
        }
        WavePacingData.Pace pace = pacing.forWave(waveIndex);
        if (pace.mode() != WavePacingData.WaveMode.SURVIVAL_RATIO) {
            return false;
        }
        int released = arcByWave.getOrDefault(key, 0);
        float ratio = pace.killRatio();
        if (released <= 0 || !(ratio > 0F) || ratio >= 1F) {
            return false;
        }
        return killsByWave.getOrDefault(key, 0) >= Math.ceil(ratio * released);
    }

    /**
     * True while the wave that just triggered still holds zombies to release.
     *
     * <p>At most one queue is ever mid-release: a wave only triggers once the one before it has
     * emptied (see {@code tick}), so this is the current wave's own queue - which is why the flag
     * it feeds is named for the current wave rather than for "some queue somewhere".
     */
    private boolean currentWaveStillReleasing() {
        for (PendingWaveSpawn queue : pendingWaveSpawns) {
            if (!queue.zombies.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** How many of the wave at this position are still standing. */
    private int aliveOfWave(int key) {
        return Math.max(0, aliveByWave.getOrDefault(key, 0));
    }

    /**
     * The health this wave's own zombies still have left, armour included.
     *
     * <p>The {@link Host#waveHealth} implementation: the director knows which zombie belongs to
     * which wave (that is {@link #waveOwner}) but not what a zombie is, so it walks the level's
     * entities and weighs the ones it owns. Armour counts because that is what the player is
     * shooting through - see {@link #healthOf}.
     *
     * @return the total, or {@code -1} when there is no map to weigh against
     */
    public int healthOfWave(int key) {
        if (waveOwner == null) {
            return -1;
        }
        int total = 0;
        for (com.pvzce.server.entity.PvzceEntity entity : host.entities()) {
            if (!(entity instanceof ZombieEntity zombie) || !zombie.isAlive()) {
                continue;
            }
            Integer owner = waveOwner.get(zombie.id());
            if (owner != null && owner == key) {
                total += healthOf(zombie);
            }
        }
        return total;
    }

    /** The identity of one wave in the run: which round, and which wave inside it. */
    private static int waveKey(int round, int index) {
        return round * 1000 + index;
    }

    /**
     * Whether a due wave has to wait for an opening wave's zombies to be gone.
     *
     * <p>The other half of {@link WaveDef#holdUntilDead(int)}: the zombies of an opening wave
     * come one at a time, and the wave after them does not walk into the back of the last one.
     * Only the *arrival* waits - the gap has already run from the trigger (see {@code tick}), so
     * a player who clears the field before the wave is due sees exactly the pacing the level
     * asked for, and one who is still fighting gets the wave as soon as the field is clear. The
     * wait is capped like the per-zombie one: a player who is losing the opening should meet the
     * second wave late, not never.
     *
     * <p>The counter is the number of ticks already spent held - it is only advanced on the ticks
     * this method was actually asked, which are the ticks the wave was due - and the flag is
     * cleared once the wait is over (either way), so a level only ever pays for this once per
     * opening wave.
     *
     * @return true while a due wave must be held back
     */
    private boolean openingWaveStillOnTheField() {
        if (!openingGateArmed) {
            return false;
        }
        if (host.aliveZombieCount() == 0 || openingGateTicks >= openingGateHoldTicks) {
            openingGateArmed = false;
            return false;
        }
        openingGateTicks++;
        return true;
    }

    private void triggerWave(WaveDef wave, int waveIndex) {
        this.waveIndex++;
        // The counter is left where the arriving wave put it - at the target it ran to. Resetting
        // it here would read as "no countdown has started yet", and the next tick would hand the
        // following wave a target of its own and, with a cleared lawn, arrive it immediately.
        waveProgress = 0F;
        waveWarningActive = false;
        waveWarningFinal = false;
        waveArrivalHeld = false;
        dirty = true;

        int holdTicks = wave.holdUntilDead(waveIndex);
        // An opening wave hands its pacing to the player, and the wave after it waits for the
        // field to be clear. Recorded here, where the wave is known, because by the time the
        // wait matters the queue is gone.
        openingGateArmed = holdTicks > 0;
        openingGateTicks = 0;
        openingGateHoldTicks = holdTicks;

        WavePacingData.Pace pace = pacing.forWave(waveIndex + 1);
        // A budget wave picks its own composition from a pool, so the entries' lane lists do
        // not apply to it: the planner answers with zombie ids and nothing else.
        List<QueuedZombie> zombies = pace.mode() == WavePacingData.WaveMode.BUDGET
                ? BudgetPlanner.plan(pace, wave.entries(), host.random()).stream()
                        .map(id -> new QueuedZombie(id, List.of(), 1F))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new))
                : expandEntries(wave.entries());
        Collections.shuffle(zombies, host.random());
        int key = waveKey(round, waveIndex);
        pendingWaveSpawns.add(new PendingWaveSpawn(zombies, shuffledRows(),
                effectiveSpawnInterval(wave), holdTicks, key,
                pace.isStockpile() ? pace.maxAlive() : 0,
                pacing.earlyKillDelayFactor(), round, waveIndex));
        // The arc is what this wave set out to send, and it is the number the survival-ratio
        // gate takes its share of. Written here, where the composition is known.
        arcByWave.put(key, zombies.size());
        releasedWaves.remove(key);


        int announcedIndex = key;
        boolean firstAnnouncement = announcedWaves.add(announcedIndex);
        // The huge-wave call belongs to the warning (see announceWaveWarning); a wave whose data
        // asks for no warning window would otherwise arrive in silence, so it is played here
        // instead. Never both: the warning marks the index as called out.
        if (firstAnnouncement && wave.isHuge() && !announcedWarnings.contains(announcedIndex)) {
            emitAtBoardCentre(PvzceSounds.AMBIENT_HUGE_WAVE);
        }
        if (firstAnnouncement && wave.type() == WaveDef.WaveType.FINAL) {
            emitAtBoardCentre(PvzceSounds.EFFECT_AWOOGA);
            // A final wave whose data asks for no banner has no earlier beat to open the graves
            // on, so this is where they open; with a banner they already did (see
            // announceWaveWarning), and doing it twice would give every stone two zombies.
            if (Math.max(0, wave.warningTicks()) == 0) {
                host.riseGraveZombies(wave);
            }
        }

        nextWaveDelayTicks = nextWaveDelayTicks(this.waveIndex);
        // A new countdown starts from zero with no target yet; the target is decided on the first
        // tick it runs, because `clearRewardApplies` wants the lawn state of that moment. With no
        // wave left there is nothing to arm, and `nextWaveTargetTicks` stays zero - which is also
        // why the arrival test cannot fire again: after the last wave both are zero but the index
        // is past the end, so this whole branch is skipped.
        if (this.waveIndex < roundWaves) {
            waveIntervalTicks = 0;
            nextWaveTargetTicks = 0;
        }
    }

    private void updateWaveWarning() {
        if (waveIndex >= roundWaves) {
            waveProgress = 1F;
            waveWarningActive = false;
            waveWarningFinal = false;
            return;
        }
        WaveDef next = waveAt(round, waveIndex);
        if (next == null) {
            waveProgress = 1F;
            waveWarningActive = false;
            waveWarningFinal = false;
            return;
        }
        int remaining = nextWaveTargetTicks - waveIntervalTicks;
        int warningTicks = Math.max(0, next.warningTicks());
        // The wave has to be counting down, not still releasing: ``tick`` freezes the counter
        // while the previous wave's zombies are coming out, so ``remaining`` no longer runs
        // negative there - but the release window still sits *before* the countdown starts, and
        // a banner that went up during it would be announcing a wave the player has already
        // been told about.
        // ``nextWaveDelayTicks`` is -1 once the last wave of the round has been released - that
        // is the "no more waves" sentinel, not a countdown of minus one. Testing it for
        // positivity is the whole fix: without it ``remaining`` was negative, the second half of
        // the range check is trivially true for a negative number, and the banner stayed lit from
        // the final wave until the level ended. That is the "a huge wave is coming" that never
        // stops.
        boolean countingDown = nextWaveTargetTicks > 0 && remaining > 0
                && remaining <= nextWaveTargetTicks;
        // A held arrival keeps its banner: the countdown has finished because the field is still
        // occupied, and a wave that is due is exactly what the banner is warning about. Without
        // this it went out at the due tick and the wave then arrived in silence, up to the
        // gate's cap later.
        boolean active = next.isHuge() && warningTicks > 0
                && (waveArrivalHeld || (countingDown && remaining <= warningTicks));
        boolean finalWarning = active && next.type() == WaveDef.WaveType.FINAL;
        if (active && !waveWarningActive) {
            announceWaveWarning(waveIndex);
        }
        if (active != waveWarningActive || finalWarning != waveWarningFinal) {
            dirty = true;
        }
        waveWarningActive = active;
        waveWarningFinal = finalWarning;
        waveProgress = nextWaveTargetTicks <= 0
                ? 1F
                : Math.max(0F, Math.min(1F, waveIntervalTicks / (float) nextWaveTargetTicks));
    }

    /**
     * Calls out a huge wave as its warning opens, which is when the original does it.
     *
     * <p>The red "a huge wave is approaching" text and Dave's line of the same words are one beat
     * on screen. Playing the sound at the wave's <em>arrival</em> instead - which is what this
     * used to do - put it six seconds after the text had faded, so the announcement was read and
     * then heard about a wave the player had already met.
     *
     * <p>Once per wave index, like {@link #triggerWave}'s own announcements: a save restored
     * inside the warning window re-enters it, and a window is a range of ticks rather than an
     * event, so "first tick of the window" is the transition that fires it.
     */
    private void announceWaveWarning(int waveIndex) {
        if (!announcedWarnings.add(waveKey(round, waveIndex))) {
            return;
        }
        emitAtBoardCentre(PvzceSounds.AMBIENT_HUGE_WAVE);
        // The graves open with the banner, not with the wave.
        //
        // The last wave's banner is the whole point of the beat: the level tells the player
        // that everything is coming, and the lawn erupting is what "everything" means. Firing
        // it at the arrival instead put it a whole warning window later - six seconds on a
        // level that asks for one - so the player read the banner, waited, and then the graves
        // opened to an empty-looking pause. A final wave with no warning window still opens
        // its graves on arrival (see triggerWave), because then the two are the same tick.
        WaveDef wave = waveAt(round, waveIndex);
        if (wave != null && wave.type() == WaveDef.WaveType.FINAL) {
            host.riseGraveZombies(wave);
        }
    }

    private void emitAtBoardCentre(Identifier sound) {
        host.emitEffect("", host.width() / 2F, host.height() / 2F, sound, 1F, 1F);
    }

    private void spawnPendingWaveZombies() {
        if (pendingWaveSpawns.isEmpty()) {
            return;
        }
        Iterator<PendingWaveSpawn> iterator = pendingWaveSpawns.iterator();
        while (iterator.hasNext()) {
            PendingWaveSpawn queue = iterator.next();
            if (queue.zombies.isEmpty()) {
                iterator.remove();
                continue;
            }
            // A stockpile wave holds a presence rather than a pace: while fewer than
            // `max_alive` of its own zombies are standing it releases proportionally faster, and
            // at the cap it waits for one of them to die. Zero means the cap is reached, which is
            // not a reason to touch the counter - it simply does not run down.
            int stockpileScale = stockpileScale(queue);
            if (stockpileScale == 0) {
                continue;
            }
            boolean gateExpired = false;
            if (queue.ticksUntilNext > 0) {
                // A gated queue is waiting for the zombie it released: the next one is due the
                // moment that zombie dies. Either way the wait ends with the counter at zero,
                // and the spawn below happens on the following tick - the same one-tick shape
                // an interval has always had, so a cap of 1200 reads as "about twenty seconds"
                // exactly like an interval of 1200 would.
                boolean previousDied = queue.waitsForPrevious()
                        && queue.gateZombieId >= 0 && !host.zombieAlive(queue.gateZombieId);
                queue.ticksUntilNext--;
                if (previousDied) {
                    // The zombie this queue was waiting for is gone: the next one is due now.
                    queue.ticksUntilNext = 0;
                } else if (queue.waitsForPrevious() && queue.ticksUntilNext <= 0) {
                    // The death gate ran out rather than being answered - that is the whole point
                    // of it being a cap, and the zombie it waited for took too long. It spawns on
                    // this tick, and the wait armed below is the tightened one, which is what
                    // stops an opening wave from becoming a stall.
                    gateExpired = true;
                } else if (queue.ticksUntilNext > 0) {
                    // An interval, still running. It fires on the tick *after* it reaches zero,
                    // which is the one-tick boundary every shipped wave table is written against:
                    // a 300-tick interval releases every 301 ticks.
                    continue;
                }
            }
            QueuedZombie queued = queue.zombies.poll();
            int row = laneFor(queued, queue);
            ZombieEntity spawned = spawnQueued(queued, row);
            if (spawned != null) {
                // Owned here, where the wave is known: the stockpile cap and the survival-ratio
                // gate both count a wave's own zombies rather than the lawn's. The health is the
                // third thing that has to be counted here and not at trigger time - see
                // `healthDrainApplies` for why the line is a share of what a wave has put out.
                waveOwner.put(spawned.id(), queue.waveKey);
                aliveByWave.merge(queue.waveKey, 1, Integer::sum);
                healthOnLawnByWave.merge(queue.waveKey, healthOf(spawned), Integer::sum);
            }
            queue.gateZombieId = queue.waitsForPrevious() && spawned != null ? spawned.id() : -1;
            // One tick above the wait, because the counter is decremented on the tick after it is
            // armed and the spawn lands when it reaches zero: a 300-tick interval releases every
            // 301 ticks and a 1200-tick cap holds for 1201, which is the boundary every shipped
            // wave table's `delay` is written against. The gate that just expired is the one case
            // where the cap itself was already spent, so it arms the next (tightened) one.
            int wait = queue.waitsForPrevious()
                    ? (gateExpired ? queue.tightenedHold() : queue.holdTicks)
                    : queue.intervalTicks;
            queue.ticksUntilNext = wait + 1;
            if (stockpileScale > 1) {
                queue.ticksUntilNext = Math.max(1, queue.ticksUntilNext / stockpileScale);
            }
            if (queue.zombies.isEmpty()) {
                iterator.remove();
                // The queue is gone but the wave is not: its deaths are still what the next
                // wave's early arrival is measured against.
                releasedWaves.add(queue.waveKey);
            }
        }
        host.flushPending();
    }

    /**
     * How much faster a stockpile wave may release, or zero when it may not release at all.
     *
     * <p>The written interval divided by this, so a wave that is one zombie short of a cap of
     * eight releases in an eighth of the time and one at its cap releases at exactly the pace the
     * author wrote. The divisor is capped by the interval itself, which is what stops "one zombie
     * per tick" from being something a level can ask for by writing a cap of sixty.
     *
     * <p>Waves in any other mode return one, and a wave with no cap never stops: this is a faster
     * <em>trickle</em>, not a spawner that refills the lawn the moment one zombie dies.
     */
    private int stockpileScale(PendingWaveSpawn queue) {
        if (!queue.waitsForStock()) {
            return 1;
        }
        int alive = aliveOfWave(queue.waveKey);
        if (alive >= queue.maxAlive) {
            return 0;
        }
        return Math.max(1, Math.min(queue.intervalTicks, queue.maxAlive - alive));
    }

    private static List<QueuedZombie> expandEntries(List<WaveDef.Entry> entries) {
        List<QueuedZombie> zombies = new ArrayList<>();
        for (WaveDef.Entry entry : entries) {
            int count = Math.max(0, entry.count());
            for (int i = 0; i < count; i++) {
                zombies.add(new QueuedZombie(entry.id(), entry.rows(), entry.healthScale()));
            }
        }
        return zombies;
    }

    /**
     * What one zombie is worth to the health drain: its body and its armour together.
     *
     * <p>The original's own total ({@code mBodyHealth + mHelmHealth}), and it has to be a total of
     * both because that is what the player is actually shooting through: a buckethead is one
     * zombie of 1100, not a 200-health zombie with a hat.
     */
    private static int healthOf(ZombieEntity zombie) {
        return Math.max(0, zombie.health()) + Math.max(0, zombie.armorHealth());
    }

    /**
     * One zombie a wave still owes, and the lanes it may arrive in.
     *
     * <p>{@code rows} empty means "any lane": the queue then walks its own shuffled list of the
     * whole board, which is what every wave table written before the pool wanted. A pool level
     * writes the lanes on the *entry* instead (floaties in the water, walkers on the grass),
     * and this carries that answer to the spawn rather than making the queue guess.
     *
     * <p>{@code healthScale} is the endless round's growth, carried per zombie rather than taken
     * from a level rule: it belongs to the wave a zombie arrived in, and a level-wide multiplier
     * would be a rule the level rewrites under a running mutation's feet.
     */
    private record QueuedZombie(Identifier id, List<Integer> rows, float healthScale) {
    }

    /**
     * One zombie of a wave, which for a bobsled may be four ordinary ones.
     *
     * <p>The original's {@code Board::CanAddBobSled} rule, kept where the wave is: a bobsled team
     * needs ice under it, and a lane that has none gets the team's worth of plain walkers instead
     * - four of them, because that is what the entry is worth, and staggered along x so they
     * arrive as a group rather than stacked on one point. The alternative the original rejects and
     * so does this: spawn nothing, and leave a wave that believes it is still owed a zombie.
     *
     * <p>Asked of the <em>chosen</em> lane rather than of the board, because the invariant is
     * "a sled is only ever dealt onto ice" - {@link #rowFor} has already preferred an iced lane
     * where one was available, so a lane without ice here means there was nowhere to sled.
     *
     * @return the entity the wave should follow (its gate zombie), which is the first of the four
     */
    private ZombieEntity spawnQueued(QueuedZombie queued, int row) {
        BobsledCapability sled = bobsledOf(queued.id());
        if (sled != null && !host.rowHasIce(row)) {
            ZombieEntity first = null;
            for (int position = 0; position <= sled.riders(); position++) {
                ZombieEntity walker = host.spawnZombie(sled.fallback(),
                        host.width() + 0.6F + position * sled.spacing(), row, queued.healthScale());
                if (first == null) {
                    first = walker;
                }
            }
            return first;
        }
        return host.spawnZombie(queued.id(), host.width() + 0.6F, row, queued.healthScale());
    }

    /** This zombie's bobsled capability, or {@code null} when it is not a sled. */
    private static BobsledCapability bobsledOf(Identifier id) {
        com.pvzce.api.content.ZombieDef def =
                com.pvzce.common.core.BuiltInRegistries.ZOMBIES.get(id);
        return def == null ? null
                : def.capability(BobsledCapability.class).orElse(null);
    }

    /**
     * The lane one zombie arrives in.
     *
     * <p>An entry that names its lanes gets exactly those (that is what {@code rows} is for, and a
     * pool level uses it to put floaties in the water and walkers on the grass). An entry that
     * names none gets the queue's shuffled whole board, <em>narrowed to the lanes this zombie can
     * actually use</em>: a walker skips the water rows and a swimmer prefers them. Without that
     * narrowing the shuffle dealt walkers into the pool - they spawned off the right edge, which
     * clamps to the last column, and drowned on their first tick.
     *
     * <p>A bobsled is narrowed the same way and for the same reason: it is dealt into an iced lane
     * when one of the lanes it may use is iced. When none is, the lanes are left alone, because the
     * spawn itself substitutes four ordinary zombies (see {@link #spawnQueued}) and refusing to
     * pick a lane would leave the wave owing one for ever.
     *
     * <p>Narrowing a shuffled list rather than reshuffling keeps the order: the same seed still
     * deals the same lanes to the same zombies, which is what makes a recorded run replay.
     *
     * <p>{@code LevelServer.spawnZombie} carries the same rule as a backstop, so this is about
     * picking the right lane in the first place rather than about being the only guard.
     */
    private int rowFor(QueuedZombie queued, PendingWaveSpawn queue) {
        List<Integer> lanes = usableLanes(queued, queued.rows().isEmpty() ? queue.rows : queued.rows());
        return lanes.get(queue.rowIndex++ % lanes.size());
    }

    /**
     * The lane one spawn arrives in: the host's forced lane for the run's opening zombie, or the
     * ordinary deal.
     *
     * <p>The forced lane is <em>offered</em> rather than imposed. It is taken only when the zombie
     * could have been dealt that lane anyway - the same "a walker is not sent into the pool" rule
     * {@link #rowFor} applies to every other spawn - so a level whose rake ended up somewhere a
     * zombie cannot walk gets the ordinary deal rather than a drowned zombie. The lane is not
     * counted against {@code rowIndex}: nothing about the queue's rotation changed, and a forced
     * lane that also advanced it would silently re-deal every later zombie of that wave.
     */
    private int laneFor(QueuedZombie queued, PendingWaveSpawn queue) {
        if (!firstSpawnDealt) {
            firstSpawnDealt = true;
            int forced = host.forcedOpeningLane(queued.id());
            if (forced >= 0) {
                List<Integer> lanes = queued.rows().isEmpty() ? queue.rows : queued.rows();
                if (usableLanes(queued, lanes).contains(forced)) {
                    return forced;
                }
            }
        }
        return rowFor(queued, queue);
    }

    /**
     * The lanes of {@code lanes} this zombie could actually be dealt, in order.
     *
     * <p>A walker skips the water rows and a swimmer prefers them; a bobsled prefers a lane with
     * ice when one of the lanes it may use has any. Narrowing a list rather than reshuffling keeps
     * the order: the same seed still deals the same lanes to the same zombies, which is what makes
     * a recorded run replay. When nothing of its own kind is left the list is returned unchanged -
     * a ducky tube on a lawn walks, and a walker on a board that is nothing but pool drowns; that
     * is the level's problem, not this method's.
     */
    private List<Integer> usableLanes(QueuedZombie queued, List<Integer> lanes) {
        com.pvzce.api.content.ZombieDef def =
                com.pvzce.common.core.BuiltInRegistries.ZOMBIES.get(queued.id());
        if (def == null) {
            return lanes;
        }
        List<Integer> usable = new ArrayList<>();
        for (int lane : lanes) {
            if (host.rowIsWater(lane) == def.canSwim()) {
                usable.add(lane);
            }
        }
        if (!usable.isEmpty()) {
            lanes = usable;
        }
        if (def.capability(BobsledCapability.class).isPresent()) {
            List<Integer> iced = new ArrayList<>();
            for (int lane : lanes) {
                if (host.rowHasIce(lane)) {
                    iced.add(lane);
                }
            }
            if (!iced.isEmpty()) {
                lanes = iced;
            }
        }
        return lanes;
    }

    /** The whole board, shuffled: the lane pool an entry that names no lanes draws from. */
    private List<Integer> shuffledRows() {
        List<Integer> rows = new ArrayList<>();
        for (int y = 0; y < host.height(); y++) {
            rows.add(y);
        }
        Collections.shuffle(rows, host.random());
        return rows;
    }

    // ------------------------------------------------------------------
    // Rounds
    // ------------------------------------------------------------------

    /** The round the run is in, one-based. */
    public int round() {
        return round;
    }

    /** How many waves the current round holds. */
    public int roundWaves() {
        return roundWaves;
    }

    /** The wave the player is in, zero-based inside the round; equals the round's length at its end. */
    public int waveInRound() {
        return Math.min(waveIndex, roundWaves);
    }

    /** Every wave's type in the current round, lowercase, as the client's banner list wants them. */
    public List<String> roundWaveTypes() {
        List<String> types = new ArrayList<>(Math.max(0, roundWaves));
        for (int index = 0; index < roundWaves; index++) {
            WaveDef wave = waveAt(round, index);
            types.add(wave == null ? "small" : wave.type().name().toLowerCase(java.util.Locale.ROOT));
        }
        return List.copyOf(types);
    }

    /**
     * True when the round is over and waiting for the player to choose their next cards.
     *
     * <p>What the level freezes on: no wave is owed, the lawn is clear, and nothing should tick
     * until the choice is in. An ordinary level never reaches this state - it wins instead.
     */
    public boolean pendingRoundClear() {
        return roundClearPending;
    }

    /**
     * Closes the round and arms the next one.
     *
     * <p>Called by the level when the player's card choice arrives (or when the wait for it runs
     * out). The per-wave bookkeeping is dropped rather than carried: the lawn is empty by
     * definition - that is what let the round close - so no zombie on the field belongs to a
     * previous round, and wave keys of a round nobody is playing any more would be a slow leak
     * on a run long enough to matter.
     *
     * @return the round that just began, one-based
     */
    public int beginNextRound() {
        round++;
        waveIndex = 0;
        roundWaves = Math.max(0, host.wavesInRound(round));
        waveIntervalTicks = 0;
        nextWaveTargetTicks = 0;
        nextWaveDelayTicks = firstWaveDelay();
        waveProgress = 0F;
        waveWarningActive = false;
        waveWarningFinal = false;
        waveArrivalHeld = false;
        openingGateArmed = false;
        openingGateTicks = 0;
        openingGateHoldTicks = 0;
        roundClearPending = false;
        releasedWaves.clear();
        aliveByWave.clear();
        killsByWave.clear();
        arcByWave.clear();
        healthOnLawnByWave.clear();
        healthDrainTarget = -1;
        waveOwner.clear();
        dirty = true;
        return round;
    }

    /**
     * How many waves the run has released in total.
     *
     * <p>The score an endless run is measured in, and the number the summary line shows. Counted
     * from the rounds already finished rather than kept as a running total, so a resumed run and
     * a fresh one agree.
     */
    public int cumulativeWaves() {
        int total = 0;
        for (int earlier = 1; earlier < round; earlier++) {
            total += Math.max(0, host.wavesInRound(earlier));
        }
        return total + waveIndex;
    }

    /**
     * True when nothing more will arrive: every wave of the round is out and no queue is left.
     *
     * <p>A level with no waves at all never answers true, which is deliberate and is the one
     * case that looks wrong: a level whose table is empty has finished its waves the moment it
     * starts, so "nothing more will arrive" holds from tick one - and the win check, which is
     * the only caller that matters, would declare a level won before the player had done
     * anything. An empty table is a test level or a sandbox, never a shipped one, and those
     * levels end the way they always did: a zombie eats the house.
     */
    public boolean allWavesReleased() {
        return roundWaves > 0 && waveIndex >= roundWaves && pendingWaveSpawns.isEmpty();
    }

    /**
     * True when the whole run is over rather than the round.
     *
     * <p>What a mechanic that produces zombies on its own clock has to ask before it may stop:
     * a grave that keeps raising one every second and a half never lets the field fall to zero,
     * so a level whose only mouths are its graves could not be finished at all. An endless
     * level never finishes, so it answers false forever - its graves keep working.
     */
    public boolean runFinished() {
        return !endless && allWavesReleased();
    }

    public boolean waveWarningActive() {
        return waveWarningActive;
    }

    public boolean waveWarningFinal() {
        return waveWarningFinal;
    }

    public boolean finalWaveActive() {
        return waveWarningActive && waveWarningFinal;
    }

    /** The HUD's view of the wave state. */
    public WaveProgressS2C progressPacket() {
        return new WaveProgressS2C(waveInRound(), Math.max(0, roundWaves), waveProgress,
                waveWarningActive, waveWarningFinal, round);
    }

    /**
     * Clears everything that only a tick would otherwise clear.
     *
     * <p>Called when the level ends: it stops ticking, and a win that lands <em>during</em> the
     * final warning would otherwise leave the banner lit on the client for good, because the
     * wave that would have turned it off is the one whose zombies just died.
     */
    public void clearOnLevelEnd() {
        waveWarningActive = false;
        waveWarningFinal = false;
        waveProgress = 1F;
        dirty = true;
    }

    /**
     * Recomputes the banner and the progress bar from the current counters.
     *
     * <p>Needed after a restore: the counters are read back from the save, but "is a huge wave
     * being warned about" is derived, and the client has to be told without waiting for a tick.
     */
    public void refreshWarning() {
        updateWaveWarning();
    }

    /** Drops every release queue; the level is being torn down. */
    public void clearQueues() {
        pendingWaveSpawns.clear();
    }

    /** True once, when the client-visible part of the state changed since the last call. */
    public boolean consumeDirty() {
        if (!dirty) {
            return false;
        }
        dirty = false;
        return true;
    }

    // ------------------------------------------------------------------
    // Save and restore
    // ------------------------------------------------------------------

    /** Writes this director's block of the level save: the position, the clock and the queues. */
    public void save(CompoundTag root) {
        root.putInt("LoadVersion", SAVE_VERSION);
        root.putInt("WaveRound", round);
        root.putInt("WaveIndexInRound", waveIndex);
        root.putInt("WaveIntervalTicks", waveIntervalTicks);
        root.putInt("NextWaveTargetTicks", nextWaveTargetTicks);
        root.putInt("OpeningGateTicks", openingGateTicks);
        root.putInt("OpeningGateHoldTicks", openingGateHoldTicks);
        if (roundClearPending) {
            root.putByte("RoundClearPending", (byte) 1);
        }
        if (openingGateArmed) {
            root.putByte("OpeningGateArmed", (byte) 1);
        }
        if (waveArrivalHeld) {
            root.putByte("WaveArrivalHeld", (byte) 1);
        }

        ListTag pendingWaves = new ListTag();
        for (PendingWaveSpawn queue : pendingWaveSpawns) {
            CompoundTag queueTag = new CompoundTag();
            ListTag zombies = new ListTag();
            ListTag zombieRows = new ListTag();
            ListTag zombieScales = new ListTag();
            for (QueuedZombie queued : queue.zombies) {
                zombies.add(new StringTag(queued.id().toString()));
                ListTag lanes = new ListTag();
                for (int lane : queued.rows()) {
                    lanes.add(new IntTag(lane));
                }
                zombieRows.add(lanes);
                zombieScales.add(new FloatTag(queued.healthScale()));
            }
            queueTag.put("Zombies", zombies);
            // Parallel to `Zombies`; a save written before a wave could name its lanes has no
            // such key, and every zombie in it reads back as "any lane" - which is what it was.
            queueTag.put("ZombieRows", zombieRows);
            // Parallel too, and read the same way: a missing or zero entry is an ordinary
            // zombie, which is what every queue held before the rounds existed.
            queueTag.put("ZombieScales", zombieScales);
            ListTag rows = new ListTag();
            for (int row : queue.rows) {
                rows.add(new IntTag(row));
            }
            queueTag.put("Rows", rows);
            queueTag.putInt("IntervalTicks", queue.intervalTicks);
            queueTag.putInt("RowIndex", queue.rowIndex);
            queueTag.putInt("TicksUntilNext", queue.ticksUntilNext);
            queueTag.putInt("HoldTicks", queue.holdTicks);
            queueTag.putInt("WaveKey", queue.waveKey);
            queueTag.putInt("WaveRound", queue.round);
            queueTag.putInt("WaveIndex", queue.waveIndexInRound);
            queueTag.putInt("MaxAlive", queue.maxAlive);
            // The zombie the gate is waiting for is deliberately not written: entity ids come
            // from a process-wide counter, so a restored id may name a different zombie (or
            // none at all). A resumed queue waits out its cap instead of trusting a number
            // that means nothing in this process.
            pendingWaves.add(queueTag);
        }
        root.put("PendingWaveSpawns", pendingWaves);
    }

    /**
     * Reads back what {@link #save} wrote.
     *
     * @throws IllegalStateException when the block was written by a version whose position this
     *         one cannot read. The caller drops the save rather than resuming into a run whose
     *         wave numbering means something else - an endless level's old save held the index
     *         into a table that no longer exists.
     */
    public void restore(CompoundTag root) {
        if (root.getInt("LoadVersion") != SAVE_VERSION) {
            throw new IllegalStateException("wave block is not version " + SAVE_VERSION
                    + " (found " + root.getInt("LoadVersion") + ")");
        }
        round = Math.max(1, root.getInt("WaveRound"));
        roundWaves = Math.max(0, host.wavesInRound(round));
        waveIndex = Math.max(0, Math.min(roundWaves, root.getInt("WaveIndexInRound")));
        // Everything below the index has already walked in, so it has already announced itself.
        // Without this, resuming a save taken after the last wave started made ``triggerWave``
        // see an empty ``announcedWaves`` and play the siren and the huge-wave call a second
        // time - the sound the player reported as looping, since it lands while that wave's
        // zombies are still coming in.
        announcedWaves.clear();
        for (int i = 0; i < waveIndex; i++) {
            announcedWaves.add(waveKey(round, i));
        }
        // The warning call-out is per wave too, but a wave below the index may still be *inside*
        // its warning window when the save was taken (the window ends when the wave arrives, and
        // arriving is what advances the index). Seeding it is therefore wrong in the one case
        // that matters - a save taken during the final warning would resume in silence - and the
        // client already refuses to repeat either announcement within a level instance, so a
        // resumed window calls out at most once more.
        announcedWarnings.clear();
        waveIntervalTicks = Math.max(0, root.getInt("WaveIntervalTicks"));
        openingGateArmed = root.getInt("OpeningGateArmed") != 0;
        waveArrivalHeld = root.getInt("WaveArrivalHeld") != 0;
        openingGateTicks = Math.max(0, root.getInt("OpeningGateTicks"));
        openingGateHoldTicks = Math.max(0, root.getInt("OpeningGateHoldTicks"));
        roundClearPending = root.getInt("RoundClearPending") != 0;
        nextWaveDelayTicks = waveIndex < roundWaves ? nextWaveDelayTicks(waveIndex) : -1;
        // A resumed countdown keeps the target it was running at: `clearRewardApplies` answers
        // differently on the tick after a resume (the lawn arrives with the entities, a moment
        // later) and a finish line that moved would stop the level dead.
        nextWaveTargetTicks = Math.max(0, root.getInt("NextWaveTargetTicks"));
        restorePendingWaves(root.getList("PendingWaveSpawns"));
        reownRestoredWaves();
        // The restored state is what the client needs to see, so the next sync must not wait for
        // a tick that would have been dirty anyway.
        dirty = true;
    }

    /**
     * Rebuilds the per-wave counts a restored save cannot carry.
     *
     * <p>Entity ids do not survive a process, so which wave a zombie belonged to is not in the
     * file. Two things follow, and both are static properties of the wave source rather than of
     * the run: every wave below the index either still has a release queue (it is releasing) or
     * has finished, and its arc is the wave's own size. The zombies on the lawn are then handed
     * to the newest waves first - the wave that just walked in owns the lawn - so a resumed
     * stockpile wave counts what it can instead of reading the field as empty.
     */
    private void reownRestoredWaves() {
        waveOwner.clear();
        aliveByWave.clear();
        killsByWave.clear();
        arcByWave.clear();
        healthOnLawnByWave.clear();
        // The drain's line and totals are not saved (see `save`), so a restored run starts its
        // current countdown without one: the mechanic comes back on the next wave that arrives,
        // which is the same "one refill" the stockpile cap pays for on a resume.
        healthDrainTarget = -1;
        releasedWaves.clear();
        Set<Integer> releasing = new HashSet<>();
        for (PendingWaveSpawn queue : pendingWaveSpawns) {
            releasing.add(queue.waveKey);
            arcByWave.put(queue.waveKey, waveSize(queue.round, queue.waveIndexInRound));
        }
        for (int index = 0; index < waveIndex; index++) {
            int key = waveKey(round, index);
            if (!releasing.contains(key)) {
                releasedWaves.add(key);
                arcByWave.putIfAbsent(key, waveSize(round, index));
            }
        }
        for (int id : host.livingZombieIds()) {
            for (int index = waveIndex - 1; index >= 0; index--) {
                int key = waveKey(round, index);
                if (releasing.contains(key) || aliveOfWave(key) >= arcByWave.getOrDefault(key, 0)) {
                    continue;
                }
                waveOwner.put(id, key);
                aliveByWave.merge(key, 1, Integer::sum);
                break;
            }
        }
    }

    /** How many zombies the wave at this position sends, as the wave source says. */
    private int waveSize(int round, int waveIndex) {
        WaveDef wave = waveAt(round, waveIndex);
        return wave == null ? 0 : wave.totalZombies();
    }

    private void restorePendingWaves(ListTag pending) {
        pendingWaveSpawns.clear();
        for (Tag element : pending.values()) {
            if (!(element instanceof CompoundTag queueTag)) {
                continue;
            }
            List<Tag> zombieTags = queueTag.getList("Zombies").values();
            List<Tag> zombieRowTags = queueTag.getList("ZombieRows").values();
            List<Tag> scaleTags = queueTag.getList("ZombieScales").values();
            List<QueuedZombie> zombieIds = new ArrayList<>();
            for (int index = 0; index < zombieTags.size(); index++) {
                if (!(zombieTags.get(index) instanceof StringTag stringTag)) {
                    continue;
                }
                Identifier zombieId = Identifier.tryParse(stringTag.value());
                if (zombieId == null) {
                    continue;
                }
                // The lanes ride beside the id, and a save without them (everything written
                // before a wave could name its lanes) reads back as "any lane".
                List<Integer> lanes = new ArrayList<>();
                if (index < zombieRowTags.size() && zombieRowTags.get(index) instanceof ListTag laneTags) {
                    for (Tag lane : laneTags.values()) {
                        if (lane instanceof IntTag intTag) {
                            lanes.add(intTag.value());
                        }
                    }
                }
                float scale = 1F;
                if (index < scaleTags.size() && scaleTags.get(index) instanceof FloatTag scaleTag
                        && scaleTag.value() > 0F) {
                    scale = scaleTag.value();
                }
                zombieIds.add(new QueuedZombie(zombieId, List.copyOf(lanes), scale));
            }
            List<Integer> rows = new ArrayList<>();
            for (Tag tag : queueTag.getList("Rows").values()) {
                if (tag instanceof IntTag intTag) {
                    rows.add(intTag.value());
                }
            }
            if (rows.isEmpty()) {
                continue;
            }
            // The interval is saved with the queue: a save taken mid-release has to
            // keep trickling at the wave's own pace, not at today's default. Same for the
            // death gate's cap - but not the zombie it was waiting for, whose id does not
            // survive a process (see the save side), so a resumed queue paces itself by the
            // cap until it releases a zombie it can follow again.
            int queueRound = Math.max(1, queueTag.getInt("WaveRound"));
            int queueWave = Math.max(0, queueTag.getInt("WaveIndex"));
            PendingWaveSpawn queue = new PendingWaveSpawn(zombieIds, rows,
                    queueTag.getInt("IntervalTicks") > 0
                            ? queueTag.getInt("IntervalTicks")
                            : WaveDef.DEFAULT_SPAWN_INTERVAL_TICKS,
                    Math.max(0, queueTag.getInt("HoldTicks")),
                    waveKey(queueRound, queueWave),
                    Math.max(0, queueTag.getInt("MaxAlive")),
                    pacing.earlyKillDelayFactor(), queueRound, queueWave);
            queue.rowIndex = Math.max(0, queueTag.getInt("RowIndex"));
            queue.ticksUntilNext = Math.max(0, queueTag.getInt("TicksUntilNext"));
            pendingWaveSpawns.add(queue);
        }
    }

    /**
     * One wave's zombies, trickling out at that wave's own pace.
     *
     * <p>The interval belongs to the wave rather than to the level: an easy level's early waves
     * should take ten seconds between zombies and its last wave three, which is a property of the
     * wave, not of the file.
     *
     * <p>{@code holdTicks} replaces that interval for a wave that paces itself by the player's
     * kills: the next zombie is due the moment the one this queue released dies, and at the
     * latest {@code holdTicks} after it was released. {@code gateZombieId} is that zombie - the
     * queue's own handle on it, not its position, because by the time the answer matters it may
     * already have been removed from the level.
     */
    private static final class PendingWaveSpawn {
        private final ArrayDeque<QueuedZombie> zombies;
        private final List<Integer> rows;
        private final int intervalTicks;
        /**
         * Ticks to wait for the previously released zombie, or 0 to use the interval.
         *
         * <p>Mutable because the death gate tightens every time it runs out; see
         * {@link #tightenedHold()}.
         */
        private int holdTicks;
        /** Which wave this queue belongs to, as {@link WaveDirector#waveKey}. */
        private final int waveKey;
        /** The round and the wave inside it, kept beside the key for the save block. */
        private final int round;
        private final int waveIndexInRound;
        /** How many of this wave's zombies may stand at once, or 0 for no stockpile. */
        private final int maxAlive;
        /** What the death gate's cap is multiplied by each time it runs out. */
        private final float holdDecay;
        private int rowIndex;
        private int ticksUntilNext;
        /** The zombie this queue is waiting for, or -1 when it is not waiting for one. */
        private int gateZombieId = -1;

        private PendingWaveSpawn(List<QueuedZombie> zombies, List<Integer> rows, int intervalTicks,
                                 int holdTicks, int waveKey, int maxAlive, float holdDecay,
                                 int round, int waveIndexInRound) {
            this.zombies = new ArrayDeque<>(zombies);
            this.rows = rows;
            this.intervalTicks = Math.max(1, intervalTicks);
            this.holdTicks = Math.max(0, holdTicks);
            this.waveKey = waveKey;
            this.maxAlive = Math.max(0, maxAlive);
            this.holdDecay = holdDecay > 0F && holdDecay < 1F ? holdDecay : 1F;
            this.round = round;
            this.waveIndexInRound = waveIndexInRound;
        }

        /** True when this queue waits for the zombie it just released. */
        private boolean waitsForPrevious() {
            return holdTicks > 0;
        }

        /** True when this queue holds a presence on the lawn rather than a pace. */
        private boolean waitsForStock() {
            return maxAlive > 0;
        }

        /**
         * Tightens the cap, and answers the tighter one.
         *
         * <p>Every expiry multiplies the cap by {@code early_kill_delay_factor}, so wave 1's
         * twenty seconds become fifteen, eleven, eight… by the fifth zombie. The floor is one
         * second: below that the gate stops being a gate, and a level that wants no gate at all
         * writes {@code "hold_until_dead": 0}.
         */
        private int tightenedHold() {
            holdTicks = Math.max(60, Math.round(holdTicks * holdDecay));
            return holdTicks;
        }
    }
}
