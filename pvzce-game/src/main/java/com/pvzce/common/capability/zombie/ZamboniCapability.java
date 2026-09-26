package com.pvzce.common.capability.zombie;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.ZombieCapability;
import com.pvzce.api.entity.EntityAnimations;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.nbt.CompoundTag;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.entity.ZombieEntity;
import com.pvzce.server.level.LevelServer;

import java.util.List;
import java.util.Optional;

/**
 * Drives over the lawn, crushing plants and leaving ice behind (the zamboni).
 *
 * <p>Not a zombie that eats: a zombie that <em>arrives</em>. Everything in its lane is destroyed
 * rather than chewed, the ground it crossed is frozen so nothing can be replanted there, and it
 * takes a great deal of damage to stop - which makes it the one thing a wall of plants cannot
 * answer, because plants are what it eats through.
 *
 * <h2>The trail</h2>
 *
 * <p>{@code pvzce:ice}, a scene element that is deliberately not plantable. That is the whole
 * mechanic: a zamboni leaves a lane the player has lost, and "lost" has to mean "cannot put another
 * peashooter there" rather than "a bit slippery". The ice is terrain, so it persists in the save
 * with the rest of the scene and a mutation or a level can clear it the same way it clears a
 * crater.
 *
 * <p>It is terrain with a clock, though, not a scar: every frozen cell melts back into lawn after
 * the level's {@code ice_melt} (thirty seconds by default), and a fire blast - the cherry bomb's or
 * the jalapeno's - takes it off the lawn at once. That timing lives on the level rather than here
 * because by the time the ice is old, the machine that made it is usually dead: see
 * {@code LevelServer.tickScene}.
 *
 * <h2>What it does not do</h2>
 *
 * <p>It does not freeze zombies. The original's zamboni leaves a trail and is a hazard on its own;
 * the "everything nearby freezes when it dies" reading is the ice-shroom's ice, and borrowing it
 * would make one zombie a second ice-shroom.
 */
public final class ZamboniCapability implements ZombieCapability {
    /** How far ahead of its own centre it crushes, in cells. */
    public static final float CRUSH_REACH = 0.6F;
    /** What kind of hit crushing a plant is. Not armour-relevant: a plant has none. */
    public static final Identifier CRUSH_DAMAGE_TYPE = PvzceIds.DAMAGE_IMPACT;

    private final float crushReach;
    private final boolean leavesIce;

    public ZamboniCapability(float crushReach, boolean leavesIce) {
        this.crushReach = Math.max(0.1F, crushReach);
        this.leavesIce = leavesIce;
    }

    public static final MapCodec<ZamboniCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("crush_reach", CRUSH_REACH)
                    .forGetter(ZamboniCapability::crushReach),
            Codec.BOOL.optionalFieldOf("leaves_ice", true).forGetter(ZamboniCapability::leavesIce)
    ).apply(i, ZamboniCapability::new));

    public float crushReach() {
        return crushReach;
    }

    public boolean leavesIce() {
        return leavesIce;
    }

    @Override
    public ZombieCapability instantiate() {
        return this;
    }

    /**
     * The machine is in the way, so the lane is closed while it is in it.
     *
     * <p>Crushing is a {@code tickMovement} hook rather than an {@code onImpact}: the zamboni does
     * not damage plants, it <em>passes through</em> them, and what it leaves behind is terrain.
     */
    @Override
    public boolean tickMovement(ZombieEntity zombie, LevelAccess level) {
        if (!(level instanceof LevelServer server)) {
            return false;
        }
        int row = zombie.gridY();
        for (int column = 0; column < server.width(); column++) {
            if (Math.abs(column + 0.5F - zombie.cellX()) > crushReach) {
                continue;
            }
            for (PlantEntity plant : server.plantsAt(column, row)) {
                if (!plant.isRemoved()) {
                    // `damageFrom` rather than `damage`: a plant that is invulnerable while its
                    // fuse burns should not be flattened by a vehicle, for the same reason the
                    // explosion path deliberately does reach it.
                    plant.damageFrom(plant.health());
                    server.emitEffect(PvzceParticles.MOWER_CLOUD.toString(),
                            plant.cellX(), plant.cellY(), null);
                }
            }
            if (leavesIce) {
                leaveIce(server, column, row);
            }
        }
        zombie.setAnimation(EntityAnimations.DRIVE);
        return false;
    }

    /** Freezes one cell, unless it is already ice or is not bare ground. */
    private static void leaveIce(LevelServer level, int column, int row) {
        var scene = level.sceneAt(column, row);
        if (scene == null) {
            return;
        }
        Identifier id = scene.id();
        if (PvzceIds.ICE.equals(id)) {
            // Already frozen. `setScene` would be a no-op, but this early return is what keeps the
            // packet below to one per cell: a zamboni sits on the same cell for ticks on end, and
            // re-sending it every tick would be a packet per tick for no change.
            return;
        }
        // Bare ground only, the same list the vase tool works off: the trail melts back into lawn
        // (the level's ice clock writes the cell's default terrain), so freezing a gravestone, a
        // pot or a crater would quietly delete it thirty seconds later. Water is not ground a
        // vehicle leaves a trail on either.
        if (!PvzceIds.GRASS.equals(id) && !PvzceIds.GROUND.equals(id)
                && !PvzceIds.ROOF_FLAT.equals(id) && !PvzceIds.ROOF_SLOPE.equals(id)) {
            return;
        }
        level.setScene(column, row, PvzceIds.ICE);
        // Writing the cell is only half of it: the client draws what it was last told, and the
        // scene mirror is what it was told. Without this the lane stayed green on screen while the
        // server had it frozen - and the zamboni levels hide `pvzce:grass`, so the cell showed the
        // backdrop and read as "nothing happened here at all".
        level.sendSceneCell(column, row);
    }

    /** The machine is spent with the zombie; its trail is the lawn's business now. */
    @Override
    public void onDeath(ZombieEntity zombie, LevelAccess level) {
        if (level instanceof LevelServer server) {
            server.emitEffect(PvzceParticles.EXPLOSION_POW.toString(),
                    zombie.cellX(), zombie.cellY(), PvzceSounds.EFFECT_EXPLOSION);
        }
    }

    @Override
    public void save(CompoundTag tag) {
        // Nothing of its own: the trail is terrain and the save carries it with the scene.
    }

    private static final class PvzceSounds {
        static final Identifier EFFECT_EXPLOSION = com.pvzce.common.PvzceSounds.EFFECT_EXPLOSION;
    }

    private static final class PvzceParticles {
        static final Identifier MOWER_CLOUD = com.pvzce.common.PvzceParticles.MOWER_CLOUD;
        static final Identifier EXPLOSION_POW = com.pvzce.common.PvzceParticles.EXPLOSION_POW;
    }
}
