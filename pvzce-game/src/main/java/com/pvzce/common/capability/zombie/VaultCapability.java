package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
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
 */
public final class VaultCapability implements ZombieCapability {
    /** Cells cleared by the hop; lands past the vaulted plant. */
    public static final float DEFAULT_JUMP_DISTANCE = 1.4F;
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
     * The slice of the vault the zombie is off the ground, as a fraction of its length.
     *
     * <p>Read off the clip: its airborne frames are 13..36 of the 43-frame mask, i.e. 0.30 to
     * 0.84. Travel outside that window would be the zombie sliding with its feet planted -
     * the same "it teleported" read the instant vault had, only slower.
     */
    private static final float AIRBORNE_FROM = 0.30F;
    private static final float AIRBORNE_TO = 0.84F;

    private final float jumpDistance;
    private final int jumpTicks;
    private final Optional<Identifier> sound;

    private boolean jumped;
    /** Ticks left of a vault in progress; 0 when not vaulting. */
    private int vaultTicks;
    /** Where the vault started, so the travel is measured from it rather than accumulated. */
    private float vaultStartX;

    public VaultCapability(float jumpDistance, Optional<Identifier> sound) {
        this(jumpDistance, DEFAULT_JUMP_TICKS, sound);
    }

    public VaultCapability(float jumpDistance, int jumpTicks, Optional<Identifier> sound) {
        this.jumpDistance = jumpDistance;
        this.jumpTicks = Math.max(1, jumpTicks);
        this.sound = sound;
    }

    public static final MapCodec<VaultCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("jump_distance", DEFAULT_JUMP_DISTANCE)
                    .forGetter(VaultCapability::jumpDistance),
            Codec.INT.optionalFieldOf("jump_ticks", DEFAULT_JUMP_TICKS)
                    .forGetter(VaultCapability::jumpTicks),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(VaultCapability::sound)
    ).apply(i, VaultCapability::new));

    public float jumpDistance() {
        return jumpDistance;
    }

    public int jumpTicks() {
        return jumpTicks;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    /** True once this zombie has vaulted; it walks like any other zombie from then on. */
    public boolean hasJumped() {
        return jumped;
    }

    /** True while the vault is in progress. */
    public boolean isVaulting() {
        return vaultTicks > 0;
    }

    @Override
    public ZombieCapability instantiate() {
        return new VaultCapability(jumpDistance, jumpTicks, sound);
    }

    /**
     * A pole vaulter jogs with its pole until it has used it.
     *
     * <p>The original has two walking clips for this one zombie - {@code anim_run} (13..49,
     * pole held out) and {@code anim_walk} (93..137, hands empty) - and the run one was never
     * exported, so the zombie jogged its post-vault walk from the moment it spawned.
     */
    @Override
    public String walkState(ZombieEntity zombie) {
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
        PlantEntity plant = level.plantAt(zombie.gridX(), zombie.gridY());
        if (plant == null) {
            return false;
        }
        startVault(zombie, level);
        return true;
    }

    private void startVault(ZombieEntity zombie, LevelAccess level) {
        vaultTicks = jumpTicks;
        vaultStartX = zombie.cellX();
        zombie.setAnimation(EntityAnimations.JUMP);
        level.emitEffect("", zombie.cellX(), zombie.cellY(),
                sound.orElseGet(() -> zombie.def().sounds().special().orElse(PvzceSounds.ZOMBIE_POLEVAULT)));
    }

    /**
     * Moves the zombie across the plant, at the clip's own pace.
     *
     * <p>Eased rather than linear: a vault is a hop, and the art's airborne window is already
     * a curve - which is why the travel is mapped onto that window instead of onto the whole
     * clip.
     */
    private void advanceVault(ZombieEntity zombie) {
        vaultTicks--;
        // Re-asserted every tick, not set once: the hop is the one thing this zombie is doing,
        // and anything that published another state mid-air (a hit, a rule, a future
        // capability) would leave the rest of the vault playing the walking clip - a zombie
        // that slides across the plant with its pole frozen at its side, which is exactly
        // what "it did not really jump" looked like.
        zombie.setAnimation(EntityAnimations.JUMP);
        float progress = 1F - vaultTicks / (float) jumpTicks;
        float travel = MathUtil.easeInOut(MathUtil.clamp01(
                (progress - AIRBORNE_FROM) / (AIRBORNE_TO - AIRBORNE_FROM)));
        zombie.setCellX(vaultStartX - jumpDistance * travel);
        if (vaultTicks <= 0) {
            // Land exactly past the plant, whatever the easing rounded to.
            jumped = true;
            zombie.setCellX(vaultStartX - jumpDistance);
            zombie.setAnimation(EntityAnimations.WALK);
        }
    }

    @Override
    public void save(CompoundTag tag) {
        tag.putInt("jumped", jumped ? 1 : 0);
        tag.putInt("vaultTicks", vaultTicks);
        tag.putFloat("vaultStartX", vaultStartX);
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
        if (vaultTicks <= 0) {
            vaultTicks = 0;
        }
    }
}
