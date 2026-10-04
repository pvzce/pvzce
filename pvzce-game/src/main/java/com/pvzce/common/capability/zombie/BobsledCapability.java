package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.DamageTypeDef;
import com.pvzce.api.content.ProjectileDef;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.PvzceParticles;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.ZombieEntity;

/**
 * The bobsled team: a lead zombie and three riders on one sled, and only on ice.
 *
 * <p>One wave entry, four bodies. The lead is the team's machine - its 300-point sled is what
 * the player shoots at, and while it holds, all four slide at {@code slide_speed} instead of
 * walking. When it breaks, the team <em>crashes</em>: there is no sled any more, so all four are
 * ordinary zombies from that tick on, at their own definition's pace, each with a small speed of
 * its own so a crashed team spreads out the way the original's does.
 *
 * <h2>Where the riders come from</h2>
 *
 * <p>The lead calls them: {@link #tick} spawns {@code riders} more bodies of its own definition
 * a little way behind itself and marks each one. That mark is the only thing that distinguishes
 * a rider, and it has to exist - a rider that spawned its own team would be four more, and four
 * more after that. It is the same shape the dancing zombie's crew has (see
 * {@code SummonDancersCapability}), with one difference: the crew here is the same content id,
 * because that is what a bobsled team is.
 *
 * <p>A rider then asks the board, every tick, whether the sled is still there: a lead of its own
 * team on its own lane, ahead of it, with an intact sled. <b>Recomputing rather than being
 * told</b> is what makes the crash survive everything that can happen to a lead - shot dead,
 * blown away by a blover, or simply not in the save file it was loaded from - without a list of
 * entity ids that the save does not carry.
 *
 * <h2>Why it needs ice</h2>
 *
 * <p>A sled on a lawn is a zombie carrying a prop. The original refuses to deal one into a lane
 * that has no ice ({@code Board::CanAddBobSled} reads {@code mIceTimer[theRow]}), and this
 * project's ice is the {@code pvzce:ice} terrain the zamboni leaves behind: {@code LevelServer}
 * sends a bobsled to an iced lane when one exists, and a wave that asks for one on a bare lawn
 * gets four ordinary zombies instead of nothing at all. The rule lives on the spawn path rather
 * than here because a lane is chosen before an entity exists - see {@code LevelServer.spawnRowFor}
 * and {@code WaveDirector.rowFor}.
 */
public final class BobsledCapability implements ZombieCapability {
    /** How many riders follow a lead, so one entry is four zombies. */
    public static final int DEFAULT_RIDERS = 3;
    /** The sled's own health: the original's {@code HELMTYPE_BOBSLED}. */
    public static final int DEFAULT_SLED_HEALTH = 300;
    /** How fast the team slides, in cells per second. */
    public static final float DEFAULT_SLIDE_SPEED = 0.55F;
    /**
     * How far apart the four are, in cells.
     *
     * <p>The original's {@code (position + 1) * 50} px. It is the sled's own length: closer and
     * the four read as one body, farther and they are four zombies who happen to be walking the
     * same way.
     */
    public static final float DEFAULT_SPACING = 0.625F;
    /**
     * How much faster or slower a crashed rider may end up than its definition, either way.
     *
     * <p>The original gives each of the four a fresh random speed the moment the sled breaks -
     * "stops treating them as a team". Ten percent is enough to pull them apart over a lane and
     * small enough that none of them is a different zombie.
     */
    public static final float DEFAULT_CRASH_SPREAD = 0.1F;

    private final int riders;
    private final int sledHealthTotal;
    private final float slideSpeed;
    private final float spacing;
    private final Identifier fallback;
    private final float crashSpread;
    private final java.util.Optional<Identifier> sound;

    /** True for the three bodies the lead called: they ride, and they never call a team. */
    private boolean rider;
    /** True once the lead has called its team, so a restored lead does not call a second one. */
    private boolean called;
    /** Remaining sled health, on the lead only. */
    private int sledHealth;
    private boolean crashed;
    /** This body's own pace after a crash, rolled when the sled breaks. */
    private float crashFactor = 1F;

    public BobsledCapability(int riders, int sledHealth, float slideSpeed, float spacing,
                             Identifier fallback, float crashSpread,
                             java.util.Optional<Identifier> sound) {
        this.riders = Math.max(0, riders);
        this.sledHealthTotal = Math.max(1, sledHealth);
        this.sledHealth = this.sledHealthTotal;
        this.slideSpeed = Math.max(0.01F, slideSpeed);
        this.spacing = Math.max(0.05F, spacing);
        this.fallback = fallback == null ? PvzceIds.id("basic_zombie") : fallback;
        this.crashSpread = Math.max(0F, crashSpread);
        this.sound = sound == null ? java.util.Optional.empty() : sound;
    }

    public static final MapCodec<BobsledCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("riders", DEFAULT_RIDERS).forGetter(BobsledCapability::riders),
            Codec.INT.optionalFieldOf("sled_health", DEFAULT_SLED_HEALTH)
                    .forGetter(BobsledCapability::sledHealthTotal),
            Codec.FLOAT.optionalFieldOf("slide_speed", DEFAULT_SLIDE_SPEED)
                    .forGetter(BobsledCapability::slideSpeed),
            Codec.FLOAT.optionalFieldOf("spacing", DEFAULT_SPACING)
                    .forGetter(BobsledCapability::spacing),
            Identifier.CODEC.optionalFieldOf("fallback", PvzceIds.id("basic_zombie"))
                    .forGetter(BobsledCapability::fallback),
            Codec.FLOAT.optionalFieldOf("crash_spread", DEFAULT_CRASH_SPREAD)
                    .forGetter(BobsledCapability::crashSpread),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(BobsledCapability::sound)
    ).apply(i, BobsledCapability::new));

    public int riders() {
        return riders;
    }

    public int sledHealthTotal() {
        return sledHealthTotal;
    }

    public float slideSpeed() {
        return slideSpeed;
    }

    public float spacing() {
        return spacing;
    }

    /**
     * What a wave deals into a lane with no ice instead of a sled.
     *
     * <p>On the capability rather than in the wave director because it is the same statement as
     * the rest of this block: "what a bobsled needs, and what a lawn that cannot give it gets".
     */
    public Identifier fallback() {
        return fallback;
    }

    public float crashSpread() {
        return crashSpread;
    }

    public java.util.Optional<Identifier> sound() {
        return sound;
    }

    /** Remaining sled health; equals the definition when the sled is untouched. */
    public int sledHealth() {
        return sledHealth;
    }

    /** True for a rider rather than a lead. */
    public boolean isRider() {
        return rider;
    }

    /** True once the sled is gone: this body is an ordinary zombie from here on. */
    public boolean hasCrashed() {
        return crashed;
    }

    @Override
    public ZombieCapability instantiate() {
        return new BobsledCapability(riders, sledHealthTotal, slideSpeed, spacing, fallback,
                crashSpread, sound);
    }

    @Override
    public void tick(ZombieEntity zombie, LevelAccess level) {
        if (rider) {
            // The sled is the lead's body, so a rider's own answer to "am I still on it" is the
            // board's: a lead of my team, on my lane, ahead of me, with an intact sled.
            if (!crashed && !sledAhead(zombie, level)) {
                crash(zombie, level);
            }
            return;
        }
        if (!called) {
            called = true;
            call(zombie, level);
        }
    }

    /**
     * The team slides; the definition's own speed is what they walk at once they crash.
     *
     * <p>A multiplier because that is the hook's shape, but the number it is built from is the
     * speed the ride clip was drawn for: {@code slide_speed / move_speed} is exactly "play the
     * ride at its own pace while travelling at it".
     */
    @Override
    public float speedMultiplier(ZombieEntity zombie) {
        if (crashed) {
            return crashFactor;
        }
        return slideSpeed / Math.max(0.0001F, zombie.def().moveSpeed());
    }

    /**
     * The sled takes the hit before the body does, exactly as worn armour would.
     *
     * <p>Only the lead has one - the riders' bodies are ordinary - and only until it breaks:
     * a sled that has crashed is not a thing that can absorb another pea.
     */
    @Override
    public boolean onProjectileHit(ZombieEntity zombie, ProjectileDef projectile, int damage,
                                   LevelAccess level) {
        return absorb(zombie, damage, level);
    }

    @Override
    public boolean onImpact(ZombieEntity zombie, int damage, LevelAccess level,
                            DamageTypeDef type) {
        return absorb(zombie, damage, level);
    }

    private boolean absorb(ZombieEntity zombie, int damage, LevelAccess level) {
        if (rider || crashed || sledHealth <= 0) {
            return false;
        }
        sledHealth = Math.max(0, sledHealth - Math.max(0, damage));
        // The hit has to be *visible*: this hook returning true is what stops the body taking
        // damage, and it also stops `ZombieEntity.damage` drawing the impact - so the spark is
        // raised here instead. Without it a player shooting the lead watched their peas vanish
        // into a zombie that never reacted.
        level.emitEffect(PvzceParticles.HIT_SPARK.toString(), zombie.position(), zombie.surfaceId(), PvzceSounds.ZOMBIE_SHIELD_HIT);
        if (sledHealth <= 0) {
            crash(zombie, level);
        }
        return true;
    }

    /** Calls the team: {@code riders} more bodies of this zombie's own definition, behind it. */
    private void call(ZombieEntity lead, LevelAccess level) {
        for (int position = 0; position < riders; position++) {
            float x = lead.cellX() + spacing * (position + 1);
            ZombieEntity follower = level.spawnZombie(lead.defId(), lead.team(), x, lead.gridY(), 1F, lead.surfaceId());
            if (follower == null) {
                continue;
            }
            BobsledCapability sled = follower.capability(BobsledCapability.class);
            if (sled != null) {
                sled.joinSled();
            }
        }
    }

    /**
     * Marks this body as one of a lead's riders.
     *
     * <p>Called by the lead on the body it just spawned, before that body's first tick - the
     * spawn is queued, not run, so there is no window in which a rider ticks as a lead.
     */
    private void joinSled() {
        rider = true;
    }

    /**
     * True while a lead of this rider's own team still has a sled on its lane, just ahead.
     *
     * <p>"Just ahead" and not merely "on the lane": two sleds dealt into one lane would otherwise
     * hold each other's riders up for ever, and a rider whose lead has crashed must not adopt the
     * sled behind it. The tolerance is one position either way, so a rider that has drawn level
     * with its lead (both stopped at the same plant) is still on the sled.
     */
    private boolean sledAhead(ZombieEntity rider, LevelAccess level) {
        float reach = (riders + 1) * spacing + 1F;
        for (ZombieEntity other : level.zombiesInRow(rider.gridY())) {
            if (other == null || other == rider || !other.isAlive()) {
                continue;
            }
            if (other.team() != rider.team()) {
                continue;
            }
            BobsledCapability sled = other.capability(BobsledCapability.class);
            if (sled == null || sled.rider || sled.crashed) {
                continue;
            }
            float ahead = rider.cellX() - other.cellX();
            if (ahead >= -spacing && ahead <= reach) {
                return true;
            }
        }
        return false;
    }

    /** The sled is gone: everybody walks from here on, at a pace of their own. */
    private void crash(ZombieEntity zombie, LevelAccess level) {
        crashed = true;
        crashFactor = 1F + (level.random().nextFloat() * 2F - 1F) * crashSpread;
        Identifier crack = sound.orElseGet(() -> zombie.def().sounds().special()
                .orElse(PvzceSounds.ZOMBIE_SHIELD_HIT));
        level.emitEffect("", zombie.position(), zombie.surfaceId(), crack);
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("rider", rider ? 1 : 0);
        tag.putInt("called", called ? 1 : 0);
        tag.putInt("sledHealth", sledHealth);
        tag.putInt("crashed", crashed ? 1 : 0);
        tag.putFloat("crashFactor", crashFactor);
    }

    @Override
    public void load(CompoundTag tag) {
        // A save with no block - one written before this zombie existed - is read as a lead with
        // a fresh sled that has not called its team yet, which is the only reading that cannot
        // lose a sled's worth of health.
        rider = tag.getInt("rider") != 0;
        called = tag.getInt("called") != 0;
        sledHealth = tag.contains("sledHealth")
                ? Math.max(0, Math.min(sledHealthTotal, tag.getInt("sledHealth")))
                : sledHealthTotal;
        crashed = tag.getInt("crashed") != 0 || (sledHealth <= 0 && !rider);
        crashFactor = tag.contains("crashFactor") ? tag.getFloat("crashFactor") : 1F;
    }
}
