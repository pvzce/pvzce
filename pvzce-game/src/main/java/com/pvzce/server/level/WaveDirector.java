package com.pvzce.server.level;

import com.pvzce.api.content.WaveDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceSounds;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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

        /** True while the entity with this id is a living zombie; corpses answer false. */
        boolean zombieAlive(int entityId);

        /** Puts one zombie on the board; returns it, or null when the id is unknown. */
        ZombieEntity spawnZombie(Identifier zombieId, float x, int row);

        /** Moves everything spawned this tick into the level's live entity list. */
        void flushPending();

        void emitEffect(String particle, float x, float y, Identifier sound, float volume, float pitch);

        /** The final wave's farewell: every grave gives up one of this wave's zombies. */
        void riseGraveZombies(WaveDef wave);
    }

    private final Host host;
    private final List<WaveDef> waves;

    private final List<PendingWaveSpawn> pendingWaveSpawns = new ArrayList<>();
    private int nextWaveIndex;
    private int waveIntervalTicks;
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

    public WaveDirector(Host host, List<WaveDef> configured, float intervalEndMultiplier) {
        this.host = host;
        this.waves = normalizeWaves(configured);
        this.intervalEndMultiplier = intervalEndMultiplier;
        this.nextWaveDelayTicks = waves.isEmpty() ? -1 : effectiveWaveDelay(0);
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
        return Math.max(1, Math.round(wave.delay() * multiplier));
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
            // reaching `nextWaveDelayTicks`.
            boolean firstWave = nextWaveIndex == 0;
            if (firstWave || !waveStillReleasing()) {
                waveIntervalTicks = Math.min(waveIntervalTicks + 1, nextWaveDelayTicks);
            }
            // Due, and then held only if an opening wave is still on the field: the clock runs
            // while the player fights, so clearing the field early never costs the gap the
            // level asked for - it only ever costs the *arrival* of a wave that is already due.
            boolean due = waveIntervalTicks >= nextWaveDelayTicks;
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
        waveIntervalTicks = 0;
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

        List<Identifier> zombies = expandEntries(wave.entries());
        Collections.shuffle(zombies, host.random());
        pendingWaveSpawns.add(new PendingWaveSpawn(zombies, shuffledRows(), wave.spawnInterval(), holdTicks));

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
    }

    private void updateWaveWarning() {
        if (nextWaveIndex >= waves.size()) {
            waveProgress = 1F;
            waveWarningActive = false;
            waveWarningFinal = false;
            return;
        }
        WaveDef next = waves.get(nextWaveIndex);
        int remaining = nextWaveDelayTicks - waveIntervalTicks;
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
        boolean countingDown = nextWaveDelayTicks > 0 && remaining > 0 && remaining <= nextWaveDelayTicks;
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
        waveProgress = nextWaveDelayTicks <= 0
                ? 1F
                : Math.max(0F, Math.min(1F, waveIntervalTicks / (float) nextWaveDelayTicks));
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
            if (queue.ticksUntilNext > 0) {
                // A gated queue is waiting for the zombie it released: the next one is due the
                // moment that zombie dies. Either way the wait ends with the counter at zero,
                // and the spawn below happens on the following tick - the same one-tick shape
                // an interval has always had, so a cap of 1200 reads as "about twenty seconds"
                // exactly like an interval of 1200 would.
                boolean previousDied = queue.waitsForPrevious()
                        && queue.gateZombieId >= 0 && !host.zombieAlive(queue.gateZombieId);
                if (previousDied) {
                    queue.ticksUntilNext = 0;
                } else {
                    queue.ticksUntilNext--;
                    continue;
                }
            }
            Identifier zombieId = queue.zombies.poll();
            int row = queue.rows.get(queue.rowIndex++ % queue.rows.size());
            ZombieEntity spawned = host.spawnZombie(zombieId, host.width() + 0.6F, row);
            queue.gateZombieId = queue.waitsForPrevious() && spawned != null ? spawned.id() : -1;
            queue.ticksUntilNext = queue.waitsForPrevious() ? queue.holdTicks : queue.intervalTicks;
            if (queue.zombies.isEmpty()) {
                iterator.remove();
            }
        }
        host.flushPending();
    }

    private static List<Identifier> expandEntries(List<WaveDef.Entry> entries) {
        List<Identifier> zombies = new ArrayList<>();
        for (WaveDef.Entry entry : entries) {
            int count = Math.max(0, entry.count());
            for (int i = 0; i < count; i++) {
                zombies.add(entry.id());
            }
        }
        return zombies;
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
            for (Identifier zombieId : queue.zombies) {
                zombies.add(new StringTag(zombieId.toString()));
            }
            queueTag.put("Zombies", zombies);
            ListTag rows = new ListTag();
            for (int row : queue.rows) {
                rows.add(new IntTag(row));
            }
            queueTag.put("Rows", rows);
            queueTag.putInt("IntervalTicks", queue.intervalTicks);
            queueTag.putInt("RowIndex", queue.rowIndex);
            queueTag.putInt("TicksUntilNext", queue.ticksUntilNext);
            queueTag.putInt("HoldTicks", queue.holdTicks);
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
        restorePendingWaves(root.getList("PendingWaveSpawns"));
        // The restored state is what the client needs to see, so the next sync must not wait for
        // a tick that would have been dirty anyway.
        dirty = true;
    }

    private void restorePendingWaves(ListTag pending) {
        pendingWaveSpawns.clear();
        for (Tag element : pending.values()) {
            if (!(element instanceof CompoundTag queueTag)) {
                continue;
            }
            List<Identifier> zombieIds = new ArrayList<>();
            for (Tag tag : queueTag.getList("Zombies").values()) {
                if (tag instanceof StringTag stringTag) {
                    Identifier zombieId = Identifier.tryParse(stringTag.value());
                    if (zombieId != null) {
                        zombieIds.add(zombieId);
                    }
                }
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
                    Math.max(0, queueTag.getInt("HoldTicks")));
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
        private final ArrayDeque<Identifier> zombies;
        private final List<Integer> rows;
        private final int intervalTicks;
        /** Ticks to wait for the previously released zombie, or 0 to use the interval. */
        private final int holdTicks;
        private int rowIndex;
        private int ticksUntilNext;
        /** The zombie this queue is waiting for, or -1 when it is not waiting for one. */
        private int gateZombieId = -1;

        private PendingWaveSpawn(List<Identifier> zombies, List<Integer> rows, int intervalTicks,
                                 int holdTicks) {
            this.zombies = new ArrayDeque<>(zombies);
            this.rows = rows;
            this.intervalTicks = Math.max(1, intervalTicks);
            this.holdTicks = Math.max(0, holdTicks);
        }

        /** True when this queue waits for the zombie it just released. */
        private boolean waitsForPrevious() {
            return holdTicks > 0;
        }
    }
}
