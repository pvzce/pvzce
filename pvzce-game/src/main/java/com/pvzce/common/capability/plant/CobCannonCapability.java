package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.level.LevelServer;
import java.util.Optional;

/**
 * The cob cannon: a plant the player aims by hand.
 *
 * <p>Every other plant in the game decides for itself when to shoot. This one is loaded on a clock
 * and then <em>waits for orders</em> - the player clicks the cannon, then clicks a cell, and a cob
 * lands there and goes off like a cherry bomb. That is the whole reason the plant exists, and it is
 * why its state is three-valued rather than a cooldown: charging, armed, and "a shot is on its way".
 *
 * <p><b>The clock is the original's.</b> Five seconds to load the first cob, 34.75 seconds after
 * that; the cob is the same blast as a cherry bomb (1800 in a 3x3), takes two to three seconds to
 * arrive, and cannot kill a full-health Gargantuar. All four numbers are content
 * ({@code plants/cob_cannon.json}) so a mod can make a faster one without a second capability.
 *
 * <h2>What the client owns and what this owns</h2>
 *
 * <p>The client owns the aiming - the reticle, the click, the cancel - and this side owns the
 * answer: whether the cannon was loaded, whether the cell exists, and what happens next. A client
 * that fires an unloaded cannon gets a refusal rather than a shot, which is the same shape every
 * other player action has ({@code PlacePlantC2S} re-derives the price rather than trusting it).
 */
public final class CobCannonCapability implements PlantCapability {
    /** Five seconds to the first cob: the original's initial load. */
    public static final int DEFAULT_INITIAL_TICKS = 300;
    /** 34.75 seconds between cobs after that, which is the original's reload. */
    public static final int DEFAULT_RELOAD_TICKS = 2085;
    /** The projectile a shot spawns; {@code data/pvzce/projectiles/cob.json}. */
    public static final Identifier DEFAULT_PROJECTILE = PvzceIds.id("cob");
    /** The same 1800 a cherry bomb deals, in the same 3x3 - see the wiki's Cob Cannon page. */
    public static final int DEFAULT_DAMAGE = 1800;
    /** How long the shoot clip plays before the cannon is merely reloading. */
    public static final int DEFAULT_SHOT_TICKS = 105;
    /** How long the "just became loaded" clip plays before the waiting loop takes over. */
    public static final int DEFAULT_ARM_TICKS = 75;

    private final com.pvzce.common.level.RateClock clock = new com.pvzce.common.level.RateClock();
    private final int initialTicks;
    private final int reloadTicks;
    private final Identifier projectile;
    private final int damage;
    private final int shotTicks;
    private final int armTicks;
    private final Optional<Identifier> sound;

    /** Ticks left before the cannon is loaded; the cannon is loaded when this reaches zero. */
    private int charge;
    /** True once armed and still waiting for the player to aim it. */
    private boolean loaded;
    /** Ticks left of the arming performance; the clip is the only thing that reads it. */
    private int arming;
    /** Ticks left of the firing performance, during which the cannon is not loaded either. */
    private int shooting;

    public CobCannonCapability(int initialTicks, int reloadTicks, Identifier projectile, int damage,
                               int shotTicks, int armTicks, Optional<Identifier> sound) {
        this.initialTicks = Math.max(0, initialTicks);
        this.reloadTicks = Math.max(1, reloadTicks);
        this.projectile = projectile == null ? DEFAULT_PROJECTILE : projectile;
        this.damage = Math.max(0, damage);
        this.shotTicks = Math.max(0, shotTicks);
        this.armTicks = Math.max(0, armTicks);
        this.sound = sound;
    }

    public static final MapCodec<CobCannonCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("initial_ticks", DEFAULT_INITIAL_TICKS)
                    .forGetter(CobCannonCapability::initialTicks),
            Codec.INT.optionalFieldOf("reload_ticks", DEFAULT_RELOAD_TICKS)
                    .forGetter(CobCannonCapability::reloadTicks),
            Identifier.CODEC.optionalFieldOf("projectile", DEFAULT_PROJECTILE)
                    .forGetter(CobCannonCapability::projectile),
            Codec.INT.optionalFieldOf("damage", DEFAULT_DAMAGE).forGetter(CobCannonCapability::damage),
            Codec.INT.optionalFieldOf("shot_ticks", DEFAULT_SHOT_TICKS)
                    .forGetter(CobCannonCapability::shotTicks),
            Codec.INT.optionalFieldOf("arm_ticks", DEFAULT_ARM_TICKS)
                    .forGetter(CobCannonCapability::armTicks),
            Identifier.CODEC.optionalFieldOf("sound").forGetter(CobCannonCapability::sound)
    ).apply(i, CobCannonCapability::new));

    public int initialTicks() {
        return initialTicks;
    }

    public int reloadTicks() {
        return reloadTicks;
    }

    public Identifier projectile() {
        return projectile;
    }

    public int damage() {
        return damage;
    }

    public int shotTicks() {
        return shotTicks;
    }

    public int armTicks() {
        return armTicks;
    }

    public Optional<Identifier> sound() {
        return sound;
    }

    @Override
    public PlantCapability instantiate() {
        CobCannonCapability copy = new CobCannonCapability(initialTicks, reloadTicks, projectile,
                damage, shotTicks, armTicks, sound);
        // A plant that has just been put down is *not* loaded: the first cob is the five seconds
        // the original makes the player wait for, and starting loaded would make that wait vanish
        // for every cannon but the first.
        copy.charge = initialTicks;
        return copy;
    }

    /** True when the player may aim this cannon. */
    public boolean loaded() {
        return loaded && shooting <= 0;
    }

    /** Ticks left before the cannon is loaded; for the HUD and for tests. */
    public int chargeLeft() {
        return Math.max(0, charge);
    }

    /**
     * Fires at a cell, or refuses.
     *
     * <p>The two refusals are the interesting part: an unloaded cannon is the one a player will
     * actually hit (they will click it while it is still charging), and it says how long is left
     * rather than going quiet. The target is checked against the board because this is reachable
     * from a packet.
     *
     * @return {@code true} when the shot left the barrel
     */
    public boolean fireAt(PlantEntity plant, LevelAccess level, int gridX, int gridY) {
        return fireAt(plant, level, gridX, gridY, plant.surfaceId());
    }
    public boolean fireAt(PlantEntity plant, LevelAccess level, int gridX, int gridY, String surface) {
        if (!(level instanceof LevelServer server)) {
            return false;
        }
        if (plant.isRemoved()) {
            return false;
        }
        if (!loaded()) {
            return false;
        }
        if (gridX < 0 || gridX >= server.width() || gridY < 0 || gridY >= server.height()
                || !server.sceneBoard().exists(surface, gridX, gridY)) {
            return false;
        }
        loaded = false;
        charge = reloadTicks;
        shooting = shotTicks;
        plant.setState(EntityAnimations.SHOOT);
        server.requestEntitySync();
        // One load, one aimed shot - unless the level's rules multiply it. The rhythm levels'
        // energy bar counts a plant's bullets per attack, and the cob cannon's attack is this
        // shell; three of them land on the cell the player picked, which is the same rule the
        // peashooter is under and reads the same way. No stagger: a shell is aimed at a cell and
        // the three are one impact as far as the lawn is concerned, which is what "three bullets"
        // means for a weapon that fires once.
        for (int i = 0, shots = Math.max(1, level.projectileCountMultiplier(plant)); i < shots; i++) {
            server.launchAimedProjectile(projectile, damage, plant, gridX, gridY, surface);
        }
        level.emitEffect("", plant.position(), plant.surfaceId(), sound.orElse(null));
        return true;
    }

    @Override
    public void tick(PlantEntity plant, LevelAccess level) {
        if (shooting > 0) {
            shooting--;
            plant.setState(EntityAnimations.SHOOT);
            return;
        }
        if (charge > 0) {
            charge = Math.max(0, charge - clock.step(plant.actionRate()));
            if (charge > 0) {
                plant.setState(EntityAnimations.IDLE);
                return;
            }
            // Just finished loading: the one-shot "armed" clip, then the waiting loop.
            loaded = true;
            arming = armTicks;
            plant.setState(EntityAnimations.ARMED);
            return;
        }
        if (arming > 0) {
            arming--;
            plant.setState(EntityAnimations.ARMED);
            return;
        }
        plant.setState(EntityAnimations.ARMED_LOOP);
    }

    @Override
    public void save(CompoundTag tag) {
        clock.save(tag);
        tag.putInt("charge", charge);
        tag.putInt("shooting", shooting);
        tag.putInt("arming", arming);
        tag.putInt("loaded", loaded ? 1 : 0);
    }

    @Override
    public void load(CompoundTag tag) {
        clock.load(tag);
        charge = Math.max(0, tag.getInt("charge"));
        shooting = Math.max(0, tag.getInt("shooting"));
        arming = Math.max(0, tag.getInt("arming"));
        loaded = tag.getInt("loaded") != 0;
    }
}
