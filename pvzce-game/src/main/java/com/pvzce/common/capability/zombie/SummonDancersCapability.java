package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.ZombieEntity;

import java.util.List;
import java.util.Optional;

/**
 * Dancing zombie: moonwalks in, calls four backup dancers, then dances forward with them.
 *
 * <p>Three phases, and each one is visible:
 *
 * <ol>
 *   <li><b>approach</b> - the zombie moonwalks at {@code approach_speed} (the original's fast
 *       entrance) until it reaches {@code summon_at_x}, which is where the original's dancer
 *       stops: the second or third column from the right;</li>
 *   <li><b>summoning</b> - it stops and raises its arms for {@code summon_ticks}. The stop is
 *       not decoration: a dancer that summoned while walking would drop its crew into a moving
 *       formation and the arm-raise clip would play over a walk cycle;</li>
 *   <li><b>dancing</b> - it walks at its own speed from then on, and any hole in the formation
 *       is filled again after {@code resummon_ticks}. The original replaces a fallen dancer
 *       about once a square for as long as the dancer lives, which is what makes the crew a
 *       threat rather than a one-off.</li>
 * </ol>
 *
 * <p>The formation is the original's cross: one dancer on the same lane a cell ahead, one a
 * cell behind, one in the lane above and one below - all at the dancer's own column. Slots
 * outside the board (a lane above the top row, a cell off the left edge) are simply not
 * filled, so a dancer in the first row calls three, exactly as the original's does.
 *
 * <p><b>Which slots are filled is recomputed from where the dancers are</b>, not remembered
 * from the entities it spawned. The two are the same thing while a level runs, but only the
 * first survives a save: entity ids are handed out when an entity is created and are not
 * written into the save, so a remembered list would come back pointing at nothing and the
 * dancer would call a second crew on top of the one it already had.
 *
 * <p>Charm comes for free and is deliberately not special-cased: the crew is spawned onto
 * <em>the dancer's own team</em>, so a hypnotised dancer summons dancers that fight for the
 * plants - the original's "Disco is Undead" achievement, with no branch for it here.
 */
public final class SummonDancersCapability implements ZombieCapability {
    /**
     * Where the original's dancer stops to summon.
     *
     * <p>The second or third column from the right on a nine-column lawn. Read as a cell
     * coordinate rather than a column so a level of a different width still means "around
     * here"; a level that cares states its own.
     */
    public static final float DEFAULT_SUMMON_AT_X = 6F;
    /**
     * The original's moonwalk: 1.5 s per cell against the dance's 5.5.
     *
     * <p>Stated as a speed rather than as a multiplier so the clip and the movement agree:
     * the exported {@code moonwalk} clip is drawn for this speed, and the walk clip for
     * {@code 0.18}.
     */
    public static final float DEFAULT_APPROACH_SPEED = 0.67F;
    /**
     * How long the arm-raise lasts.
     *
     * <p>The original's {@code anim_armraise} is 22 frames at 24 fps (0.92 s); this is a
     * little shorter, because the clip hands back to the walk when it ends and a server that
     * kept asking for the raise would restart it for the last frame or two.
     */
    public static final int DEFAULT_SUMMON_TICKS = 48;
    /**
     * How long a hole in the formation stays open before it is filled again.
     *
     * <p>Twenty seconds, and that is a floor rather than a rhythm: a crew that is replaced as
     * fast as it is killed is not a crew the player can ever finish off, and the original's
     * dancer is a threat for what he brings once, not for what he keeps bringing.
     */
    public static final int DEFAULT_RESUMMON_TICKS = 1200;
    /**
     * How close a dancer has to be to a formation slot to count as standing in it.
     *
     * <p>Half a cell: the crew walks at the dancer's speed, so a member is either on its
     * mark or it is gone. Wide enough that a zombie nudged by a mower or a bite is still
     * recognised as being in place, narrow enough that a dancer one cell off is not.
     */
    private static final float SLOT_TOLERANCE = 0.5F;

    /** The original's cross, in the order it fills: ahead, behind, above, below. */
    private static final List<int[]> FORMATION = List.of(
            new int[]{-1, 0},
            new int[]{1, 0},
            new int[]{0, -1},
            new int[]{0, 1});

    /** What this zombie is doing; the phases are strictly ordered. */
    private enum Phase {
        APPROACH,
        SUMMONING,
        DANCING
    }

    private final Identifier dancer;
    private final int count;
    private final float summonAtX;
    private final float approachSpeed;
    private final int summonTicks;
    private final int resummonTicks;
    private final Optional<Identifier> sound;

    private Phase phase = Phase.APPROACH;
    /** Ticks left of the arm raise while summoning, or of the refill cooldown while dancing. */
    private int timer;

    public SummonDancersCapability(Identifier dancer, int count, float summonAtX, float approachSpeed,
                                   int summonTicks, int resummonTicks, Optional<Identifier> sound) {
        this.dancer = dancer;
        this.count = Math.max(0, Math.min(FORMATION.size(), count));
        this.summonAtX = summonAtX;
        this.approachSpeed = Math.max(0.01F, approachSpeed);
        this.summonTicks = Math.max(1, summonTicks);
        this.resummonTicks = Math.max(1, resummonTicks);
        this.sound = sound == null ? Optional.empty() : sound;
    }

    public static final MapCodec<SummonDancersCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Identifier.CODEC.optionalFieldOf("dancer", PvzceIds.id("backup_dancer"))
                    .forGetter(SummonDancersCapability::dancer),
            Codec.INT.optionalFieldOf("count", FORMATION.size())
                    .forGetter(SummonDancersCapability::count),
            Codec.FLOAT.optionalFieldOf("summon_at_x", DEFAULT_SUMMON_AT_X)
                    .forGetter(SummonDancersCapability::summonAtX),
            Codec.FLOAT.optionalFieldOf("approach_speed", DEFAULT_APPROACH_SPEED)
                    .forGetter(SummonDancersCapability::approachSpeed),
            Codec.INT.optionalFieldOf("summon_ticks", DEFAULT_SUMMON_TICKS)
                    .forGetter(SummonDancersCapability::summonTicks),
            Codec.INT.optionalFieldOf("resummon_ticks", DEFAULT_RESUMMON_TICKS)
                    .forGetter(SummonDancersCapability::resummonTicks),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(SummonDancersCapability::sound)
    ).apply(i, SummonDancersCapability::new));

    public Identifier dancer() {
        return dancer;
    }

    public int count() {
        return count;
    }

    public float summonAtX() {
        return summonAtX;
    }

    public float approachSpeed() {
        return approachSpeed;
    }

    public int summonTicks() {
        return summonTicks;
    }

    public int resummonTicks() {
        return resummonTicks;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    /** True once the crew has been called; the zombie is dancing from then on. */
    public boolean hasSummoned() {
        return phase == Phase.DANCING;
    }

    @Override
    public ZombieCapability instantiate() {
        return new SummonDancersCapability(dancer, count, summonAtX, approachSpeed, summonTicks,
                resummonTicks, sound);
    }

    @Override
    public void tick(ZombieEntity zombie, LevelAccess level) {
        switch (phase) {
            case APPROACH -> {
                if (zombie.cellX() <= summonAtX) {
                    phase = Phase.SUMMONING;
                    timer = summonTicks;
                }
            }
            case SUMMONING -> {
                // Published here rather than through `walkState`: a summoning dancer does not
                // walk this tick (`tickMovement` answered true), and the walk loop - the only
                // caller of `walkState` - is exactly what a taken-over tick skips. Setting the
                // state from `walkState` alone left the arm raise invisible: the pose was
                // chosen and never published.
                zombie.setAnimation(EntityAnimations.ARM_RAISE);
                if (--timer > 0) {
                    return;
                }
                phase = Phase.DANCING;
                timer = resummonTicks;
                call(zombie, level);
            }
            case DANCING -> {
                if (--timer > 0) {
                    return;
                }
                timer = resummonTicks;
                call(zombie, level);
            }
        }
    }

    /**
     * A dancer that is calling its crew stands still.
     *
     * <p>Only for the arm raise: the moonwalk is faster than the dance but it is still a
     * walk, and stopping during it would leave a zombie frozen mid-stride for a second.
     */
    @Override
    public boolean tickMovement(ZombieEntity zombie, LevelAccess level) {
        return phase == Phase.SUMMONING;
    }

    /**
     * The entrance is faster than the dance.
     *
     * <p>Asked as a multiplier because that is the hook's shape, but authored as the speed the
     * clip was drawn for: {@code approach_speed / move_speed} is exactly "play the moonwalk at
     * its own pace while travelling at it".
     */
    @Override
    public float speedMultiplier(ZombieEntity zombie) {
        if (phase != Phase.APPROACH) {
            return 1F;
        }
        return approachSpeed / Math.max(0.0001F, zombie.def().moveSpeed());
    }

    /**
     * Which gait the walk loop publishes.
     *
     * <p>Asked here rather than set from {@link #tick} because the walk loop runs after the
     * capabilities and publishes its own state on the same tick - anything set there would be
     * overwritten before the client ever heard about it.
     */
    @Override
    public String walkState(ZombieEntity zombie) {
        // The moonwalk is the approach's gait; the arm raise belongs to `tick`, because the
        // walking loop does not run while this capability has taken the tick over.
        return phase == Phase.APPROACH ? EntityAnimations.MOONWALK : null;
    }

    /**
     * Fills every empty slot of the formation.
     *
     * <p>Called once when the crew is first called and again every {@code resummon_ticks}
     * afterwards; the scan is over the three rows the formation touches, which is where a
     * member of the crew can be.
     */
    private void call(ZombieEntity zombie, LevelAccess level) {
        boolean summonedAny = false;
        for (int slot = 0; slot < count; slot++) {
            int[] offset = FORMATION.get(slot);
            int row = zombie.gridY() + offset[1];
            float x = zombie.cellX() + offset[0];
            if (row < 0 || row >= level.height() || x < 0 || x > level.width()) {
                continue;
            }
            if (occupied(level, zombie, row, x)) {
                continue;
            }
            if (level.spawnZombie(dancer, zombie.team(), x, row) != null) {
                summonedAny = true;
            }
        }
        if (summonedAny) {
            // The crew arrives to the original's own sting; without it the formation simply
            // appears, which is the one thing about this zombie the player is meant to notice.
            level.emitEffect("", zombie.cellX(), zombie.cellY(),
                    sound.orElseGet(() -> zombie.def().sounds().special()
                            .orElse(PvzceSounds.ZOMBIE_DANCER)));
        }
    }

    /**
     * True when one of this dancer's own crew already stands at {@code (x, row)}.
     *
     * <p>Its own crew, by kind and by side: a hypnotised dancer's dancers are on the other
     * team and must not be mistaken for holes in the formation - and must not stop this one
     * from calling its own either.
     */
    private boolean occupied(LevelAccess level, ZombieEntity dancer, int row, float x) {
        return level.zombiesInRow(row).stream()
                .filter(other -> other != null && !other.isRemoved())
                .filter(other -> other.defId().equals(this.dancer))
                .filter(other -> other.team() == dancer.team())
                .anyMatch(other -> Math.abs(other.cellX() - x) <= SLOT_TOLERANCE);
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("phase", phase.ordinal());
        tag.putInt("timer", timer);
    }

    @Override
    public void load(CompoundTag tag) {
        // A save with no block - one written before this zombie existed - is read as a dancer
        // that has not summoned yet, which is the only phase the file could have meant.
        int ordinal = tag.getInt("phase");
        phase = ordinal >= 0 && ordinal < Phase.values().length
                ? Phase.values()[ordinal] : Phase.APPROACH;
        timer = Math.max(0, tag.getInt("timer"));
        if (phase == Phase.DANCING && timer <= 0) {
            // A save taken between two refills: wait a full interval rather than refilling on
            // the first tick, which would let a reload hand the player a free crew.
            timer = resummonTicks;
        }
    }
}
