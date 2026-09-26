package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceConstants;
import com.pvzce.common.PvzceSounds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.common.util.MathUtil;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;

import java.util.Optional;

/**
 * Pole vaulter: runs at the first plant it meets, vaults over it, and walks on.
 *
 * <p>The vault is a <b>state with a duration</b>, not an instant. It used to be one tick
 * that teleported the zombie 1.4 cells and asked for the {@code jump} clip - which the walk
 * loop then overwrote before the client was ever told about it, so the zombie simply appeared
 * on the far side of the plant. Three things follow from making it a state, and all three are
 * what the original does:
 *
 * <ul>
 *   <li>the zombie stops walking for {@link #DEFAULT_JUMP_TICKS} ticks while the clip plays;</li>
 *   <li>its position moves across the clip's own airborne window, so the hop and the travel
 *       are one motion rather than two unrelated ones;</li>
 *   <li>before the vault it walks the {@code run} clip (pole in hand) and afterwards the
 *       {@code walk} one, which is why that clip had to be exported at all.</li>
 * </ul>
 *
 * <p>It stays an ordinary zombie while it vaults: shots land, statuses apply and a mower
 * flattens it. Nothing here is invulnerable - the original's vaulting zombie can be shot out
 * of the air, and that is most of what the plant it is jumping over is for.
 *
 * <h2>The same motion, repeating ({@code pvzce:bounce})</h2>
 *
 * <p>The pogo zombie is this capability on a cycle rather than once: {@link #bounces()} makes
 * it look for a plant to clear at the start of every vault, carry itself exactly far enough to
 * land {@link #DEFAULT_CLEARANCE} cells past it, and start again the moment it lands. The
 * original's own numbers are here rather than in a second implementation - the airborne window
 * below, the eased travel and the tall-nut rule are shared, because they are one motion and a
 * copy of them would be the copy that drifts.
 *
 * <p>What a bounce does <em>not</em> share is the end of the story: a vaulter has one plant to
 * clear and then walks, while a pogo bounces for as long as it lives, never eats (it is
 * airborne for the whole of every cycle, so the walk-and-bite step never gets a turn) and is
 * answered only by a tall-nut - which snaps the stick and turns it into the ordinary walking
 * zombie {@link #walkState} stops overriding.
 */
public final class VaultCapability implements ZombieCapability {
    /**
     * Cells cleared by the hop; lands past the vaulted plant.
     *
     * <p><b>Not a taste decision: it is the distance the art draws.</b> The {@code jump} clip's own
     * foot bones travel 1.077 cells from the first frame to the last, so a server that moved the
     * zombie 1.4 slid its landing nearly a third of a cell past the frame the sprite is drawn on -
     * the reported "撑杆僵尸/海豚僵尸动画的掉落位置和实际位置不符合". {@code tools/vault_curve.py}
     * prints the number; change it and the curve together.
     *
     * <p>It is also still enough to clear a plant: the vault is triggered from
     * {@code plantX + clearance}, so 1.08 lands the zombie about a third of a cell past the thing it
     * jumped over.
     */
    public static final float DEFAULT_JUMP_DISTANCE = 1.0908F;
    /**
     * How long the vault takes, in ticks.
     *
     * <p>The original's {@code anim_jump} is 43 frames at 12 fps, i.e. 3.58s = 215 ticks. This
     * is a few ticks short of that on purpose: the clip hands over to {@code walk} when it
     * ends, and a server still asking for {@code jump} at that moment would restart the
     * one-shot for the last frame or two - a visible hitch at the landing.
     */
    public static final int DEFAULT_JUMP_TICKS = 210;
    /**
     * How long one bounce of the pogo's stick takes, in ticks.
     *
     * <p>The original's {@code POGO_BOUNCE_TIME}: a high bounce in place and then the forward
     * one, over one cycle. The exported {@code anim_pogo} mask is eleven frames and the
     * converter plays it at the rate that puts exactly one bounce on these 80 ticks.
     */
    public static final int DEFAULT_BOUNCE_TICKS = 80;
    /**
     * How far past the plant a bounce lands it, in cells.
     *
     * <p>The original's 60 px in {@code mVelX = (mX - plantX + 60) / 80}. It is what makes the
     * bounce a <em>clear</em> rather than a lucky landing: a hop that only reached the plant's
     * own centre would leave the zombie standing on it, and the next cycle would start from
     * inside the thing it is supposed to be jumping over.
     */
    public static final float DEFAULT_CLEARANCE = 0.75F;
    /**
     * The slice of the vault the zombie is off the ground, as a fraction of its length.
     *
     * <p>Read off the clip: its airborne frames are 13..36 of the 43-frame mask, i.e. 0.30 to
     * 0.84. Travel outside that window would be the zombie sliding with its feet planted -
     * the same "it teleported" read the instant vault had, only slower. It is the pogo's own
     * shape too, and for the same reason: a cycle that moved its whole distance at a constant
     * velocity is a zombie gliding, not one bouncing.
     */
    private static final float AIRBORNE_FROM = 0.30F;
    private static final float AIRBORNE_TO = 0.84F;

    /**
     * Where the jumping clips draw the zombie, as a fraction of the hop, over clip progress.
     *
     * <p>Read off the converted {@code jump} clip by {@code tools/vault_curve.py}, from the
     * <b>torso</b>: every body bone agrees on the span to within a few per cent, and the torso is
     * the one the player reads as "where the zombie is". Normalised so the first frame is 0 and the
     * last is exactly 1, so a hop of {@link #jumpDistance} cells ends where the server says.
     *
     * <p>The dolphin rider's clip is the same motion authored the same way, so one table answers
     * both. Regenerate with {@code python3 tools/vault_curve.py} after any change to the clip.
     */
    private static final float[] VAULT_TRAVEL_KEYS = {
            -0.0000F, -0.0056F, -0.0117F, -0.0169F, -0.0171F, +0.0413F, +0.0997F, +0.1511F,
            +0.1708F, +0.1868F, +0.1907F, +0.1947F, +0.2003F, +0.2113F, +0.2217F, +0.2320F,
            +0.2408F, +0.2495F, +0.2859F, +0.3598F, +0.4332F, +0.4889F, +0.5255F, +0.5624F,
            +0.5928F, +0.6188F, +0.6448F, +0.6708F, +0.7386F, +0.8286F, +0.8783F, +0.9103F,
            +0.9310F, +0.9481F, +0.9718F, +0.9961F, +1.0113F, +1.0239F, +1.0197F, +1.0124F,
            +1.0063F, +1.0001F, +1.0000F,
    
    };

    /**
     * How far off the ground the jumping clips draw the zombie's feet, in cells.
     *
     * <p>The arc itself, from the same tool: the lowest foot's bottom edge, zeroed on the first
     * frame. The entity's own {@code height} follows it, which is what makes the legs tuck under a
     * zombie that is off the ground rather than stretch below one that is not - the hop and the
     * lift are one motion and were being told as two.
     */
    private static final float[] VAULT_LIFT_KEYS = {
            +0.0000F, +0.0027F, +0.0062F, +0.0079F, +0.0158F, +0.0670F, +0.0628F, +0.0532F,
            +0.0531F, +0.0532F, +0.0530F, +0.0531F, +0.0542F, +0.0660F, +0.1812F, +0.4069F,
            +0.5006F, +0.5859F, +0.6640F, +0.7323F, +0.8003F, +0.8504F, +0.8806F, +0.9106F,
            +0.8848F, +0.8141F, +0.7431F, +0.6719F, +0.5546F, +0.4129F, +0.3284F, +0.2691F,
            +0.2122F, +0.1564F, +0.0780F, +0.0054F, -0.0023F, +0.0030F, +0.0007F, -0.0008F,
            -0.0034F, -0.0059F, -0.0060F,
    
    };

    /** {@link #VAULT_TRAVEL_KEYS} as a sampler: evenly spaced keys over progress 0..1. */
    private static final Curve VAULT_TRAVEL = new Curve(VAULT_TRAVEL_KEYS);
    /** {@link #VAULT_LIFT_KEYS} as a sampler. */
    private static final Curve VAULT_LIFT = new Curve(VAULT_LIFT_KEYS);

    /**
     * How far across the hop the jumping art draws the zombie at {@code progress}.
     *
     * <p>Public because it is a fact about the *art*, and the test that keeps the art and the
     * simulation together has to read both: this is one half, the exported clip's own foot
     * translation keys are the other. {@code PoleVaultTest#theArtAndTheSimulationAgreeOnWhereTheZombieIs}
     * checks them against each other, so a clip re-export that moves the zombie without this table
     * being regenerated fails instead of silently drawing the landing half a cell out.
     */
    public static float travelAt(float progress) {
        return VAULT_TRAVEL.sample(progress);
    }

    /** The matching foot lift, in cells; see {@link #VAULT_LIFT_KEYS}. */
    public static float liftAt(float progress) {
        return VAULT_LIFT.sample(progress);
    }

    /** Evenly spaced keys over a 0..1 progress, linearly interpolated. */
    private record Curve(float[] keys) {
        float sample(float progress) {
            float position = MathUtil.clamp01(progress) * (keys.length - 1);
            int index = (int) position;
            if (index >= keys.length - 1) {
                return keys[keys.length - 1];
            }
            float fraction = position - index;
            return keys[index] * (1F - fraction) + keys[index + 1] * fraction;
        }
    }

    private final float jumpDistance;
    private final int jumpTicks;
    private final Optional<Identifier> sound;
    private final boolean bounce;
    private final float clearance;

    private boolean jumped;
    /** Ticks left of a vault in progress; 0 when not vaulting. */
    private int vaultTicks;
    /** Where the vault started, so the travel is measured from it rather than accumulated. */
    private float vaultStartX;
    /** How far this vault carries the zombie: fixed for a vaulter, re-read for a bounce. */
    private float vaultDistance;
    /** The height the vault started at, which {@link #VAULT_LIFT_KEYS} is added to. */
    private float vaultBaseHeight;

    public VaultCapability(float jumpDistance, Optional<Identifier> sound) {
        this(jumpDistance, DEFAULT_JUMP_TICKS, sound, false, DEFAULT_CLEARANCE);
    }

    public VaultCapability(float jumpDistance, int jumpTicks, Optional<Identifier> sound) {
        this(jumpDistance, jumpTicks, sound, false, DEFAULT_CLEARANCE);
    }

    public VaultCapability(float jumpDistance, int jumpTicks, Optional<Identifier> sound,
                           boolean bounce, float clearance) {
        this.jumpDistance = jumpDistance;
        this.jumpTicks = Math.max(1, jumpTicks);
        this.sound = sound == null ? Optional.empty() : sound;
        this.bounce = bounce;
        this.clearance = Math.max(0F, clearance);
        this.vaultDistance = jumpDistance;
    }

    public static final MapCodec<VaultCapability> CODEC = codec(false, DEFAULT_JUMP_TICKS);

    /**
     * The pogo stick's capability: the same vault, on a cycle.
     *
     * <p>A second codec rather than a second class, because the two are one motion and the only
     * thing that differs between them is the default of {@code bounce} (and the cycle a bounce
     * runs on). Two implementations of "hop across the plant, easing over the clip's airborne
     * frames" is exactly the kind of pair that drifts: the tall-nut rule and the airborne window
     * would have to be fixed twice, and the second fix is the one nobody remembers.
     */
    public static final MapCodec<VaultCapability> BOUNCE_CODEC = codec(true, DEFAULT_BOUNCE_TICKS);

    private static MapCodec<VaultCapability> codec(boolean bounceByDefault, int cycleTicks) {
        return RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.FLOAT.optionalFieldOf("jump_distance", DEFAULT_JUMP_DISTANCE)
                        .forGetter(VaultCapability::jumpDistance),
                Codec.INT.optionalFieldOf("jump_ticks", cycleTicks)
                        .forGetter(VaultCapability::jumpTicks),
                Identifier.CODEC.optionalFieldOf("sound").forGetter(VaultCapability::sound),
                Codec.BOOL.optionalFieldOf("bounce", bounceByDefault)
                        .forGetter(VaultCapability::bounces),
                Codec.FLOAT.optionalFieldOf("clearance", DEFAULT_CLEARANCE)
                        .forGetter(VaultCapability::clearance)
        ).apply(i, VaultCapability::new));
    }

    public float jumpDistance() {
        return jumpDistance;
    }

    public int jumpTicks() {
        return jumpTicks;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    /** True for the pogo zombie: the vault repeats instead of being used up. */
    public boolean bounces() {
        return bounce;
    }

    public float clearance() {
        return clearance;
    }

    /**
     * True once this zombie has used its vault up - which for a bounce means the stick.
     *
     * <p>One flag for both, and the identical consequence: from here on it walks like any other
     * zombie. A vaulter spends it on the plant it clears; a pogo loses it to a tall-nut.
     */
    public boolean hasJumped() {
        return jumped;
    }

    /** True while the vault is in progress. */
    public boolean isVaulting() {
        return vaultTicks > 0;
    }

    /**
     * True while this zombie is still on its stick: the pogo's own "not broken yet".
     *
     * <p>Nothing about the board is in the answer - a pogo bounces until the stick snaps,
     * including at the house, where {@code checkReachedLeft} is what reports that it arrived
     * (see {@link #bounce}). A <em>charmed</em> one is the exception, and only because a bounce
     * has one direction: it has been turned around, so it walks back up the lane like every other
     * body the hypno-shroom has taken (see {@link #bounce}).
     */
    private boolean stillBouncing(ZombieEntity zombie) {
        return bounce && !jumped && zombie.walkDirection() <= 0F;
    }

    @Override
    public ZombieCapability instantiate() {
        return new VaultCapability(jumpDistance, jumpTicks, sound, bounce, clearance);
    }

    /**
     * A pole vaulter jogs with its pole until it has used it.
     *
     * <p>The original has two walking clips for this one zombie - {@code anim_run} (13..49,
     * pole held out) and {@code anim_walk} (93..137, hands empty) - and the run one was never
     * exported, so the zombie jogged its post-vault walk from the moment it spawned.
     *
     * <p>A bouncing pogo is the same question with the other answer: it walks on nothing at
     * all - it is on the stick for the whole of every cycle, so the walk loop never runs and
     * this is only ever asked the moment the stick snaps.
     */
    @Override
    public String walkState(ZombieEntity zombie) {
        if (bounce) {
            return stillBouncing(zombie) ? EntityAnimations.POGO : null;
        }
        return jumped ? null : EntityAnimations.RUN;
    }

    @Override
    public boolean tickMovement(ZombieEntity zombie, LevelAccess level) {
        if (vaultTicks > 0) {
            advanceVault(zombie);
            return true;
        }
        if (jumped) {
            return false;
        }
        if (bounce) {
            return bounce(zombie, level);
        }
        PlantEntity plant = level.plantAt(zombie.gridX(), zombie.gridY());
        if (plant == null) {
            return false;
        }
        if (isTallNut(plant)) {
            // The tall-nut's whole reason to exist. A vaulter that met one stops and eats it like
            // any other zombie, and `jumped` stays false - it has not used its pole up, it simply
            // cannot get over this.
            return false;
        }
        startVault(zombie, level, jumpDistance);
        return true;
    }

    /**
     * One bounce: find the plant the zombie is about to reach, and hop it.
     *
     * <p>Three outcomes, and they are the whole of the pogo:
     *
     * <ul>
     *   <li>a <b>tall-nut</b> in reach - the stick snaps ({@code PogoBreak} in the original) and
     *       the zombie is an ordinary walker from this tick on. It answers {@code false} so the
     *       walk loop runs on the same tick and it starts eating the thing that stopped it;</li>
     *   <li>any other <b>plant</b> in reach - the hop is measured to land {@link #clearance}
     *       cells past <em>that plant</em>, which is the original's
     *       {@code mVelX = (mX - plantX + 60) / 80}. A fixed hop distance would land on the
     *       plant as often as past it, and a pogo standing inside a plant it never eats is a
     *       zombie the player cannot dislodge;</li>
     *   <li><b>nothing</b> in reach - an ordinary bounce, which carries it
     *       {@code move_speed} worth of ground. That is what keeps a pogo between plants no
     *       faster than a walker: its advantage is that it does not stop to chew, not that it
     *       travels faster.</li>
     * </ul>
     */
    private boolean bounce(ZombieEntity zombie, LevelAccess level) {
        if (!stillBouncing(zombie)) {
            // Charmed. A hop only goes one way, and this zombie has been turned around: what is
            // left is an ordinary charmed walker, which is also the only body `checkReachedLeft`
            // knows how to take off the board from the right-hand edge.
            return false;
        }
        // The edge bookkeeping the walk loop would otherwise do: a bounce takes every tick over,
        // so nothing else would ever report that this zombie reached the house - and a pogo that
        // bounced at the door for ever is a level that cannot end. `checkReachedLeft` is what
        // `FlyCapability` calls for the same reason.
        zombie.checkReachedLeft(level);
        PlantEntity plant = plantToClear(zombie, level);
        if (plant != null && isTallNut(plant)) {
            breakStick(zombie, level);
            return false;
        }
        float nominal = zombie.moveSpeed(level) * jumpTicks / PvzceConstants.TICKS_PER_SECOND;
        float distance = nominal;
        if (plant != null) {
            // Past the plant, never short of it: `clearance` cells beyond its centre.
            distance = Math.max(nominal, zombie.cellX() - (plant.cellX() - clearance));
        }
        startVault(zombie, level, distance);
        return true;
    }

    /**
     * The plant this bounce is for: the nearest one in front, within {@link #jumpDistance} cells.
     *
     * <p>"In front" is the way a zombie walks, which is the only direction a pogo ever travels.
     * The reach is a cell and a half rather than the zombie's own cell because the hop has to be
     * decided <em>before</em> the zombie is standing in the plant: by then the ordinary walk
     * would have started eating it.
     */
    private PlantEntity plantToClear(ZombieEntity zombie, LevelAccess level) {
        PlantEntity best = null;
        for (int column = 0; column < level.width(); column++) {
            PlantEntity plant = level.plantAt(column, zombie.gridY());
            if (plant == null || plant.isRemoved()) {
                continue;
            }
            float ahead = zombie.cellX() - plant.cellX();
            if (ahead < 0F || ahead > jumpDistance) {
                continue;
            }
            if (best == null || plant.cellX() > best.cellX()) {
                best = plant;
            }
        }
        return best;
    }

    /** The one tall-nut test in this file, so the vaulter and the pogo cannot disagree. */
    private static boolean isTallNut(PlantEntity plant) {
        return com.pvzce.common.core.PlantPlacement.is(plant.def(),
                com.pvzce.common.tag.PvzceTags.TALL);
    }

    /**
     * The stick snaps on a tall-nut and the zombie walks from here on.
     *
     * <p>It is not "the vault was used up" in the vaulter's sense - the zombie never got over
     * anything - but the consequences are identical and {@link #hasJumped()} is the one flag
     * both the walk loop and {@link #walkState} already read.
     */
    private void breakStick(ZombieEntity zombie, LevelAccess level) {
        jumped = true;
        zombie.setAnimation(EntityAnimations.WALK);
        // The original's "bonk". This project's own bonk event is the Whack-a-Zombie mallet's
        // (see PvzceSounds.EFFECT_BONK), so the stick breaking plays the pogo's own sound: the
        // stick is what broke, and that is the sound it makes.
        level.emitEffect("", zombie.cellX(), zombie.cellY(), soundOr(zombie));
    }

    private void startVault(ZombieEntity zombie, LevelAccess level, float distance) {
        vaultTicks = jumpTicks;
        vaultStartX = zombie.cellX();
        vaultDistance = distance;
        // The height it was standing at, which the hop's lift is added to. A vaulting zombie that
        // started on a lily pad comes back down onto the pad rather than into the water.
        vaultBaseHeight = zombie.height();
        zombie.setAnimation(bounce ? EntityAnimations.POGO : EntityAnimations.JUMP);
        level.emitEffect("", zombie.cellX(), zombie.cellY(), soundOr(zombie));
    }

    /** What this zombie's vault (or bounce) sounds like; each has its own event. */
    private Identifier soundOr(ZombieEntity zombie) {
        Identifier fallback = bounce ? PvzceSounds.ZOMBIE_POGO : PvzceSounds.ZOMBIE_POLEVAULT;
        return sound.orElseGet(() -> zombie.def().sounds().special().orElse(fallback));
    }

    /**
     * Moves the zombie across the plant, at the clip's own pace.
     *
     * <p><b>A vaulter travels by the art's own curve; a pogo travels by an eased window.</b> The
     * two are different answers because the two are different clips. The pole vaulter's and the
     * dolphin rider's {@code jump} is one authored hop with a crouch, a launch, a peak and a
     * landing settle, and its foot bones say where the zombie is on every frame; {@link
     * #VAULT_TRAVEL_KEYS} and {@link #VAULT_LIFT_KEYS} are that motion, so the sprite is drawn
     * exactly where the simulation puts it. The previous version eased the travel across an
     * airborne window of its own invention, which is a different curve from the art's - the drawn
     * landing was half a cell from the real one, which is the reported "撑杆僵尸/海豚僵尸动画的掉落
     * 位置和实际位置不符合".
     *
     * <p>The lift is the other half of the same bug: with the entity's {@code height} left at
     * zero, the art's tucked legs were drawn hanging below the ground line the whole way across.
     *
     * <p>The pogo's clip is a stick bouncing in place ({@code anim_pogo} is 11 frames with no
     * horizontal travel at all), so there is no curve to read and the eased window is the only
     * description of the motion there is - and its own bounce already draws the vertical half.
     */
    private void advanceVault(ZombieEntity zombie) {
        vaultTicks--;
        // Re-asserted every tick, not set once: the hop is the one thing this zombie is doing,
        // and anything that published another state mid-air (a hit, a rule, a future
        // capability) would leave the rest of the vault playing the walking clip - a zombie
        // that slides across the plant with its pole frozen at its side, which is exactly
        // what "it did not really jump" looked like.
        zombie.setAnimation(bounce ? EntityAnimations.POGO : EntityAnimations.JUMP);
        float progress = MathUtil.clamp01(1F - vaultTicks / (float) jumpTicks);
        if (bounce) {
            float travel = MathUtil.easeInOut(MathUtil.clamp01(
                    (progress - AIRBORNE_FROM) / (AIRBORNE_TO - AIRBORNE_FROM)));
            zombie.setCellX(vaultStartX - vaultDistance * travel);
        } else {
            zombie.setCellX(vaultStartX - vaultDistance * travelAt(progress));
            // The entity's own field, not a second drawing offset: the client already lifts the
            // art by `height`, so the feet are off the ground exactly where the clip draws them.
            zombie.setHeight(vaultBaseHeight + liftAt(progress));
        }
        if (vaultTicks <= 0) {
            // Land exactly past the plant, whatever the curve rounded to.
            jumped = !bounce;
            zombie.setCellX(vaultStartX - vaultDistance);
            zombie.setHeight(vaultBaseHeight);
            zombie.setAnimation(bounce ? EntityAnimations.POGO : EntityAnimations.WALK);
        }
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("jumped", jumped ? 1 : 0);
        tag.putInt("vaultTicks", vaultTicks);
        tag.putFloat("vaultStartX", vaultStartX);
        tag.putFloat("vaultDistance", vaultDistance);
        tag.putFloat("vaultBaseHeight", vaultBaseHeight);
    }

    @Override
    public void load(CompoundTag tag) {
        jumped = tag.getInt("jumped") != 0;
        // A save taken mid-vault resumes it: the zombie is between the plant and its landing
        // spot, and dropping the count would leave it standing inside the plant it was in the
        // middle of clearing. A save written before the vault had a duration has no start
        // position to measure from, and is read as already landed - which is where that
        // version's one-tick vault had already put it.
        vaultTicks = tag.contains("vaultStartX") ? tag.getInt("vaultTicks") : 0;
        vaultStartX = tag.getFloat("vaultStartX");
        // A save from before the bounce: the distance was the definition's, which is what the
        // field is initialised to.
        vaultDistance = tag.contains("vaultDistance") ? tag.getFloat("vaultDistance") : jumpDistance;
        // A save from before the hop had a lift: the zombie's own height is the base, which is
        // what it must come back down to.
        vaultBaseHeight = tag.contains("vaultBaseHeight") ? tag.getFloat("vaultBaseHeight") : 0F;
        if (vaultTicks <= 0) {
            vaultTicks = 0;
        }
    }
}
