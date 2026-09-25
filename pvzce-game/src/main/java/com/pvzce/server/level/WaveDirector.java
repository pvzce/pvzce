package com.pvzce.server.level;

import com.pvzce.api.content.WaveDef;
import com.pvzce.api.content.WavePacingData;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.level.mechanic.BudgetPlanner;
import com.pvzce.common.nbt.CompoundTag;
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
 * <p>What it needs from the level is small and one-directional, so it asks through {@link Host}:
 * spawning, counting and asking after zombies, the board's size, the level's random source, the
 * two sounds a wave can make, and the scene operation the final wave performs (opening graves).
 * Nothing here touches entities, scene or save format directly except its own block.
 *
 * <p>The save block keeps the key names it had when it lived in {@code LevelServer.save}: a save
 * written by an earlier build of this branch is not a compatibility promise (the project is
 * unpublished), but the tests that pin the round trip are, and the keys are what they read.
 */
public final class WaveDirector {
    private static final Logger LOGGER = LoggerFactory.getLogger("PVZCE/Waves");

    /** Everything a wave needs from the level it runs in. */
    public interface Host {
        int width();

        int height();

        java.util.Random random();

        /** Zombies still standing, corpses excluded. */
        long aliveZombieCount();

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

        /** Puts one zombie on the board; returns it, or null when the id is unknown. */
        ZombieEntity spawnZombie(Identifier zombieId, float x, int row);

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
    private final List<WaveDef> waves;
    /**
     * How this level's waves behave beyond the wave table; see {@link WavePacingData}.
     *
     * <p>Every level has one - a level that declares no {@code pvzce:wave_pacing} runs on the
     * engine's default, which {@code LevelMechanics.effective} injects. The clear bonus, the
     * death gate's tightening and the per-wave mode table all read it, each behind its own
     * switch, so a level that wants none of them writes the neutral value.
     */
    private final WavePacingData pacing;

    private final List<PendingWaveSpawn> pendingWaveSpawns = new ArrayList<>();
    private int nextWaveIndex;
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
    /** Set when the HUD-visible part of the wave state changed and the client has not heard. */
    private boolean dirty = true;
    private final Set<Integer> announcedWaves = new HashSet<>();
    private final Set<Integer> announcedWarnings = new HashSet<>();

    /**
     * Which wave owns each zombie still on the lawn, by entity id.
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
    /**
     * Waves whose release queue is gone but whose deaths still matter.
     *
     * <p>A queue is removed the moment its last zombie is out, and that is exactly when the
     * survival-ratio gate starts caring, so "is this wave released" has to outlive the queue.
     */
    private final Set<Integer> releasedWaves = new HashSet<>();

    public WaveDirector(Host host, List<WaveDef> configured, float intervalEndMultiplier,
                        WavePacingData pacing) {
        this.host = host;
        this.waves = normalizeWaves(configured);
        this.pacing = pacing == null ? WavePacingData.DEFAULT : pacing;
        this.intervalEndMultiplier = intervalEndMultiplier;
        this.nextWaveDelayTicks = waves.isEmpty() ? -1 : effectiveWaveDelay(0);
        this.nextWaveTargetTicks = 0;
    }

    /** The pacing this level runs on, for the tests and for the level's own reporting. */
    public WavePacingData pacing() {
        return pacing;
    }

    private final float intervalEndMultiplier;

    /**
     * Cross-field normalization: a final wave may only be the last one, and the
     * last wave is always treated as final even when the data omits the type.
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

    /**
     * Effective delay of a wave after applying the level's linear interval curve.
     * The first wave uses the configured delay as-is; the last wave uses
     * {@code wave_interval_end_multiplier}.
     */
    private int effectiveWaveDelay(int waveIndex) {
        WaveDef wave = waves.get(waveIndex);
        int total = waves.size();
        float endMultiplier = Float.isFinite(intervalEndMultiplier) ? intervalEndMultiplier : 1F;
        endMultiplier = Math.max(0.05F, Math.min(10F, endMultiplier));
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
        if (nextWaveIndex < waves.size()) {
            // A wave's delay is the gap *between waves*, so it starts once the previous
            // wave has finished releasing, not the moment it was triggered. Counting
            // from the trigger let two waves' release queues run at once, and zombies
            // from different waves then arrived a few seconds apart instead of on the
            // pacing their own wave asked for - the "they all come out together" of a
            // level whose first waves are authored ten seconds apart.
            //
            // The counter is frozen rather than held at its target so the warning window
            // stays a property of the gap the author wrote: the warning shows for
            // `warning_ticks` before the wave arrives, and the arrival is this countdown
            // reaching its target.
            boolean firstWave = nextWaveIndex == 0;
            if (firstWave || !waveStillReleasing()) {
                // Two things can make this countdown shorter than written, and both are the
                // answer to "I killed everything and the level is making me stand here": a
                // cleared lawn (see `clearRewardApplies`) and a wave the player has already
                // answered (the survival-ratio mode, see `earnedEarlyArrival`). The target is
                // decided on the countdown's first tick and then frozen, both because the clear
                // bonus is a reward for the state the player reached and because a finish line
                // that moved under a running countdown would stop the level dead.
                if (waveIntervalTicks == 0) {
                    nextWaveTargetTicks = clearRewardApplies()
                            ? pacing.clearRewardDelay(nextWaveDelayTicks)
                            : nextWaveDelayTicks;
                }
                waveIntervalTicks = Math.min(waveIntervalTicks + delayStep(), nextWaveTargetTicks);
            }
            // Due, and then held only if an opening wave is still on the field: the clock runs
            // while the player fights, so clearing the field early never costs the gap the
            // level asked for - it only ever costs the *arrival* of a wave that is already due.
            //
            // `waveIntervalTicks > 0` is what says a countdown has actually run: the counter is
            // frozen at zero while the previous wave is still releasing, and a "due" test that
            // read that zero as "the wait is over" arrived every following wave on the tick the
            // one before it was triggered.
            boolean due = waveIntervalTicks > 0 && waveIntervalTicks >= nextWaveTargetTicks;
            waveArrivalHeld = due && openingWaveStillOnTheField();
            if (due && !waveArrivalHeld) {
                triggerWave(waves.get(nextWaveIndex), nextWaveIndex);
            } else {
                updateWaveWarning();
            }
        } else {
            waveProgress = 1F;
            waveWarningActive = false;
            waveWarningFinal = false;
            waveArrivalHeld = false;
        }
        spawnPendingWaveZombies();
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
        Integer waveIndex = waveOwner.remove(entityId);
        if (waveIndex == null) {
            return;
        }
        aliveByWave.merge(waveIndex, -1, Integer::sum);
        killsByWave.merge(waveIndex, 1, Integer::sum);
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
        if (aliveOfWave(nextWaveIndex - 1) > 0) {
            return false;
        }
        return host.aliveZombieCount() == 0;
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
        if (nextWaveIndex <= 0 || nextWaveIndex >= waves.size()) {
            return false;
        }
        int behind = nextWaveIndex - 1;
        if (!releasedWaves.contains(behind)) {
            return false;
        }
        WavePacingData.Pace pace = pacing.forWave(behind + 1);
        if (pace.mode() != WavePacingData.WaveMode.SURVIVAL_RATIO) {
            return false;
        }
        int released = arcByWave.getOrDefault(behind, 0);
        float ratio = pace.killRatio();
        if (released <= 0 || !(ratio > 0F) || ratio >= 1F) {
            return false;
        }
        return killsByWave.getOrDefault(behind, 0) >= Math.ceil(ratio * released);
    }

    /**
     * True while any queue still holds zombies: the wave is on the field but not yet
     * fully announced, and the next wave's countdown has to wait for it.
     */
    private boolean waveStillReleasing() {
        for (PendingWaveSpawn queue : pendingWaveSpawns) {
            if (!queue.zombies.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** How many of the wave at this index are still standing. */
    private int aliveOfWave(int waveIndex) {
        return Math.max(0, aliveByWave.getOrDefault(waveIndex, 0));
    }

    /**
     * Whether a due wave has to wait for an opening wave's zombies to be gone.
     *
     * <p>The other half of {@link WaveDef#holdUntilDead(int)}: the zombies of an opening wave
     * come one at a time, and the wave after them does not walk into the back of the last one.
     * Only the *arrival* waits - the delay has already run, so a player who clears the field
     * before the wave is due sees exactly the pacing the level asked for, and one who is still
     * fighting gets the wave as soon as the field is clear. The wait is capped like the
     * per-zombie one: a player who is losing the opening should meet the second wave late, not
     * never.
     *
     * <p>The counter is the number of ticks already spent held, and the flag is cleared once the
     * wait is over (either way), so a level only ever pays for this once per opening wave.
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
        nextWaveIndex++;
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
                        .map(id -> new QueuedZombie(id, List.of()))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new))
                : expandEntries(wave.entries());
        Collections.shuffle(zombies, host.random());
        pendingWaveSpawns.add(new PendingWaveSpawn(zombies, shuffledRows(),
                effectiveSpawnInterval(wave), holdTicks, waveIndex,
                pace.isStockpile() ? pace.maxAlive() : 0,
                pacing.earlyKillDelayFactor()));
        // The arc is what this wave set out to send, and it is the number the survival-ratio
        // gate takes its share of. Written here, where the composition is known.
        arcByWave.put(waveIndex, zombies.size());
        releasedWaves.remove(waveIndex);

        int announcedIndex = waveIndex;
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

        nextWaveDelayTicks = nextWaveIndex < waves.size() ? effectiveWaveDelay(nextWaveIndex) : -1;
        // A new countdown starts from zero with no target yet; the target is decided on the first
        // tick it runs, because `clearRewardApplies` wants the lawn state of that moment. With no
        // wave left there is nothing to arm, and `nextWaveTargetTicks` stays zero - which is also
        // why the arrival test cannot fire again: after the last wave both are zero but the index
        // is past the end, so this whole branch is skipped.
        if (nextWaveIndex < waves.size()) {
            waveIntervalTicks = 0;
            nextWaveTargetTicks = 0;
        }
    }

    private void updateWaveWarning() {
        if (nextWaveIndex >= waves.size()) {
            waveProgress = 1F;
            waveWarningActive = false;
            waveWarningFinal = false;
            return;
        }
        WaveDef next = waves.get(nextWaveIndex);
        int remaining = nextWaveTargetTicks - waveIntervalTicks;
        int warningTicks = Math.max(0, next.warningTicks());
        // The wave has to be counting down, not still releasing: ``tick`` freezes the counter
        // while the previous wave's zombies are coming out, so ``remaining`` no longer runs
        // negative there - but the release window still sits *before* the countdown starts, and
        // a banner that went up during it would be announcing a wave the player has already
        // been told about.
        // ``nextWaveDelayTicks`` is -1 once the last wave has been released - that is the "no
        // more waves" sentinel, not a countdown of minus one. Testing it for positivity is the
        // whole fix: without it ``remaining`` was negative, the second half of the range check
        // is trivially true for a negative number, and the banner stayed lit from the final wave
        // until the level ended. That is the "a huge wave is coming" that never stops.
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
            announceWaveWarning(nextWaveIndex);
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
        if (!announcedWarnings.add(waveIndex)) {
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
        if (waves.get(waveIndex).type() == WaveDef.WaveType.FINAL) {
            host.riseGraveZombies(waves.get(waveIndex));
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
            int row = queued.rowFor(queue.rows, queue.rowIndex++);
            ZombieEntity spawned = host.spawnZombie(queued.id(), host.width() + 0.6F, row);
            if (spawned != null) {
                // Owned here, where the wave is known: the stockpile cap and the survival-ratio
                // gate both count a wave's own zombies rather than the lawn's.
                waveOwner.put(spawned.id(), queue.waveIndex);
                aliveByWave.merge(queue.waveIndex, 1, Integer::sum);
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
                releasedWaves.add(queue.waveIndex);
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
        int alive = aliveOfWave(queue.waveIndex);
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
                zombies.add(new QueuedZombie(entry.id(), entry.rows()));
            }
        }
        return zombies;
    }

    /**
     * One zombie a wave still owes, and the lanes it may arrive in.
     *
     * <p>{@code rows} empty means "any lane": the queue then walks its own shuffled list of the
     * whole board, which is what every wave table written before the pool wanted. A pool level
     * writes the lanes on the *entry* instead (floaties in the water, walkers on the grass),
     * and this carries that answer to the spawn rather than making the queue guess.
     */
    private record QueuedZombie(Identifier id, List<Integer> rows) {
        /** The lane to use, given the queue's own shuffled order and how many it has sent. */
        int rowFor(List<Integer> anyRow, int index) {
            List<Integer> lanes = rows.isEmpty() ? anyRow : rows;
            return lanes.get(index % lanes.size());
        }
    }

    private List<Integer> shuffledRows() {
        List<Integer> rows = new ArrayList<>();
        for (int y = 0; y < host.height(); y++) {
            rows.add(y);
        }
        Collections.shuffle(rows, host.random());
        return rows;
    }

    /** The wave the player is in, 1-based once the first wave has arrived. */
    public int currentWave() {
        return Math.min(nextWaveIndex, waves.size());
    }

    public int totalWaves() {
        return waves.size();
    }

    /** Every wave's type, lowercase, as the client's banner list wants them. */
    public java.util.List<String> waveTypes() {
        return waves.stream().map(wave -> wave.type().name().toLowerCase(java.util.Locale.ROOT)).toList();
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

    /**
     * True when nothing more will arrive: every wave has been released and no queue is left.
     *
     * <p>What the win check asks before it can call a level won, so it lives here rather than
     * being spelled out as a pair of field reads on the level.
     */
    public boolean allWavesReleased() {
        return !waves.isEmpty() && nextWaveIndex >= waves.size() && pendingWaveSpawns.isEmpty();
    }

    /** The HUD's view of the wave state. */
    public WaveProgressS2C progressPacket() {
        return new WaveProgressS2C(currentWave(), totalWaves(), waveProgress,
                waveWarningActive, waveWarningFinal);
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

    /** Writes this director's block of the level save: index, clock, gates and the queues. */
    public void save(CompoundTag root) {
        root.putInt("NextWaveIndex", nextWaveIndex);
        root.putInt("WaveIntervalTicks", waveIntervalTicks);
        root.putInt("NextWaveTargetTicks", nextWaveTargetTicks);
        root.putInt("OpeningGateTicks", openingGateTicks);
        root.putInt("OpeningGateHoldTicks", openingGateHoldTicks);
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
            for (QueuedZombie queued : queue.zombies) {
                zombies.add(new StringTag(queued.id().toString()));
                ListTag lanes = new ListTag();
                for (int lane : queued.rows()) {
                    lanes.add(new IntTag(lane));
                }
                zombieRows.add(lanes);
            }
            queueTag.put("Zombies", zombies);
            // Parallel to `Zombies`; a save written before a wave could name its lanes has no
            // such key, and every zombie in it reads back as "any lane" - which is what it was.
            queueTag.put("ZombieRows", zombieRows);
            ListTag rows = new ListTag();
            for (int row : queue.rows) {
                rows.add(new IntTag(row));
            }
            queueTag.put("Rows", rows);
            queueTag.putInt("IntervalTicks", queue.intervalTicks);
            queueTag.putInt("RowIndex", queue.rowIndex);
            queueTag.putInt("TicksUntilNext", queue.ticksUntilNext);
            queueTag.putInt("HoldTicks", queue.holdTicks);
            queueTag.putInt("WaveIndex", queue.waveIndex);
            queueTag.putInt("MaxAlive", queue.maxAlive);
            // The zombie the gate is waiting for is deliberately not written: entity ids come
            // from a process-wide counter, so a restored id may name a different zombie (or
            // none at all). A resumed queue waits out its cap instead of trusting a number
            // that means nothing in this process.
            pendingWaves.add(queueTag);
        }
        root.put("PendingWaveSpawns", pendingWaves);
    }

    /** Reads back what {@link #save} wrote. A save without a wave block keeps wave zero. */
    public void restore(CompoundTag root) {
        nextWaveIndex = Math.max(0, Math.min(waves.size(), root.getInt("NextWaveIndex")));
        // Everything below the index has already walked in, so it has already announced itself.
        // Without this, resuming a save taken after the last wave started made ``triggerWave``
        // see an empty ``announcedWaves`` and play the siren and the huge-wave call a second
        // time - the sound the player reported as looping, since it lands while that wave's
        // zombies are still coming in.
        announcedWaves.clear();
        for (int i = 0; i < nextWaveIndex; i++) {
            announcedWaves.add(i);
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
        nextWaveDelayTicks = nextWaveIndex < waves.size() ? effectiveWaveDelay(nextWaveIndex) : -1;
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
     * file. Two things follow, and both are static properties of the wave table rather than of
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
        releasedWaves.clear();
        Set<Integer> releasing = new HashSet<>();
        for (PendingWaveSpawn queue : pendingWaveSpawns) {
            releasing.add(queue.waveIndex);
            arcByWave.put(queue.waveIndex, waveSize(queue.waveIndex));
        }
        for (int index = 0; index < nextWaveIndex; index++) {
            if (!releasing.contains(index)) {
                releasedWaves.add(index);
                arcByWave.putIfAbsent(index, waveSize(index));
            }
        }
        for (int id : host.livingZombieIds()) {
            for (int index = nextWaveIndex - 1; index >= 0; index--) {
                if (releasing.contains(index) || aliveOfWave(index) >= arcByWave.getOrDefault(index, 0)) {
                    continue;
                }
                waveOwner.put(id, index);
                aliveByWave.merge(index, 1, Integer::sum);
                break;
            }
        }
    }

    /** How many zombies the wave at this index sends, as the wave table says. */
    private int waveSize(int waveIndex) {
        return waveIndex >= 0 && waveIndex < waves.size() ? waves.get(waveIndex).totalZombies() : 0;
    }

    private void restorePendingWaves(ListTag pending) {
        pendingWaveSpawns.clear();
        for (Tag element : pending.values()) {
            if (!(element instanceof CompoundTag queueTag)) {
                continue;
            }
            List<Tag> zombieTags = queueTag.getList("Zombies").values();
            List<Tag> zombieRowTags = queueTag.getList("ZombieRows").values();
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
                zombieIds.add(new QueuedZombie(zombieId, List.copyOf(lanes)));
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
            PendingWaveSpawn queue = new PendingWaveSpawn(zombieIds, rows,
                    queueTag.getInt("IntervalTicks") > 0
                            ? queueTag.getInt("IntervalTicks")
                            : WaveDef.DEFAULT_SPAWN_INTERVAL_TICKS,
                    Math.max(0, queueTag.getInt("HoldTicks")),
                    Math.max(0, queueTag.getInt("WaveIndex")),
                    Math.max(0, queueTag.getInt("MaxAlive")),
                    pacing.earlyKillDelayFactor());
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
        /** Which wave this queue belongs to, for the per-wave zombie counts. */
        private final int waveIndex;
        /** How many of this wave's zombies may stand at once, or 0 for no stockpile. */
        private final int maxAlive;
        /** What the death gate's cap is multiplied by each time it runs out. */
        private final float holdDecay;
        private int rowIndex;
        private int ticksUntilNext;
        /** The zombie this queue is waiting for, or -1 when it is not waiting for one. */
        private int gateZombieId = -1;

        private PendingWaveSpawn(List<QueuedZombie> zombies, List<Integer> rows, int intervalTicks,
                                 int holdTicks, int waveIndex, int maxAlive, float holdDecay) {
            this.zombies = new ArrayDeque<>(zombies);
            this.rows = rows;
            this.intervalTicks = Math.max(1, intervalTicks);
            this.holdTicks = Math.max(0, holdTicks);
            this.waveIndex = waveIndex;
            this.maxAlive = Math.max(0, maxAlive);
            this.holdDecay = holdDecay > 0F && holdDecay < 1F ? holdDecay : 1F;
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
