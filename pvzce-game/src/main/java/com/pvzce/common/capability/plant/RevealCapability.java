package com.pvzce.common.capability.plant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.entity.LevelAccess;
import com.pvzce.common.PvzceIds;
import com.pvzce.server.entity.PlantEntity;
import com.pvzce.server.level.LevelServer;

/**
 * Lights up the fog around itself (the plantern, and the much weaker lamp a torchwood carries).
 *
 * <p>World 4 is played behind a fog the player cannot see through, and the plantern is the answer:
 * a lamp that pushes a circle of it back. Nothing about the fog is the simulation's - it is a fact
 * about the picture - so this capability's whole job is to tell the level that a lamp exists, and
 * to take that back when the lamp is eaten.
 *
 * <p>The torchwood carries one too, and that is the original's own second lamp rather than a
 * liberty taken here: {@code Board::UpdateFog} clears the fog around any standing
 * {@code SEED_PLANTERN} <em>and</em> any standing {@code SEED_TORCHWOOD}, the latter with a reach
 * of one cell against the plantern's four. A torchwood is a fire, and a fire in the dark is how
 * the player's own report reads it - "the torchwood ought to give a faint light too".
 *
 * <h2>How the circle reaches the renderer</h2>
 *
 * <p>Not through this packet. The fog's span travels on {@code MechanicSyncS2C} as three numbers,
 * and a reveal is a <em>plant</em> - so the client already knows where it is and how big its lamp
 * is (it is in the definition the client received). Sending a second copy would be the same fact on
 * the wire twice, and the two copies could disagree for a frame.
 *
 * <p>What the server keeps instead is the <em>folded</em> answer: {@code LevelServer.fogData}
 * reports the span and {@code FogMechanic.lampsOf} the lamps, and the client draws exactly those
 * through {@code FogMechanic.alphaAt}. So "how dark is it here" has one answer on each side,
 * derived from the same inputs, and neither has to model the other.
 *
 * <h2>Why the capability and not the definition</h2>
 *
 * <p>A reveal is a live fact - the lamp is eaten and the fog comes back - so it has to be registered
 * while the plant exists and withdrawn when it stops. {@code onPlaced} and {@code onRemoved} are
 * exactly those two moments, and they already exist.
 */
public final class RevealCapability implements PlantCapability {
    /**
     * How far the lamp reaches, in cells.
     *
     * <p>The original's own plantern: {@code ClearFogAroundPlant(plant, 4)} in its own grid, which
     * clears three columns either side and two rows above and below - a reach of about three
     * cells. See {@code FogMechanic.LAMP_CORE} for how much of that is cleared outright.
     */
    public static final float DEFAULT_RADIUS = 3F;
    /** How much of the fog inside the circle it clears, 0..1. 1 is "this cell is fully lit". */
    public static final float DEFAULT_STRENGTH = 1F;

    private final float radius;
    private final float strength;
    private final boolean halo;

    public RevealCapability(float radius, float strength) {
        this(radius, strength, true);
    }

    public RevealCapability(float radius, float strength, boolean halo) {
        this.halo = halo;
        this.radius = Math.max(0.1F, radius);
        this.strength = Math.max(0F, Math.min(1F, strength));
    }

    public static final MapCodec<RevealCapability> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.FLOAT.optionalFieldOf("radius", DEFAULT_RADIUS).forGetter(RevealCapability::radius),
            Codec.FLOAT.optionalFieldOf("strength", DEFAULT_STRENGTH)
                    .forGetter(RevealCapability::strength),
            Codec.BOOL.optionalFieldOf("halo", true).forGetter(RevealCapability::halo)
    ).apply(i, RevealCapability::new));

    public boolean halo() { return halo; }

    @Override
    public void onRestored(PlantEntity plant, LevelAccess level) {
        onPlaced(plant, level);
    }

    public float radius() {
        return radius;
    }

    public float strength() {
        return strength;
    }

    @Override
    public PlantCapability instantiate() {
        return this;
    }

    @Override
    public void onPlaced(PlantEntity plant, LevelAccess level) {
        if (level instanceof LevelServer server) {
            server.addFogReveal(plant.id(), plant.cellX(), plant.cellY(), radius, strength);
        }
    }

    @Override
    public void onRemoved(PlantEntity plant, LevelAccess level) {
        if (level instanceof LevelServer server) {
            server.removeFogReveal(plant.id());
        }
    }

    /** The registry id this capability is registered under; for the level's own bookkeeping. */
    public static com.pvzce.api.util.Identifier id() {
        return PvzceIds.PLANT_CAPABILITY_REVEAL;
    }
}
