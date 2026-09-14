package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.util.Identifier;

import java.util.Optional;

/**
 * One thing a zombie carries that visibly wears out: a cone, a bucket, a screen door, the
 * flag zombie's flag.
 *
 * <p>The art for a worn piece is not something the reanim can say. The original swaps the
 * drawing in code - a beaten cone is a different PNG from a fresh one, and none of those
 * PNGs is referenced by a track - so the model carries one bone per drawing
 * ({@code cone_1}, {@code cone_2}, {@code cone_3}, all hidden) and this record says which
 * family belongs together and what wears it down. The client then draws exactly one member
 * of the family, on the frames where the clip draws the family at all (see
 * {@code EquipmentArt}).
 *
 * <p>Two things can wear a piece down, and which one is a data question:
 *
 * <ul>
 *   <li><b>armor</b> ({@code piece} names an {@link ArmorDef} id): the piece's own remaining
 *       durability. This is the cone, the bucket, the door and the newspaper - things that
 *       absorb shots, shatter, and are gone afterwards;</li>
 *   <li><b>the body</b> ({@code piece} absent): the zombie's own health, with
 *       {@code health_below} being the fraction under which the worn drawing appears. This
 *       is the flag, which nothing shoots off a zombie but which the original does show
 *       ragged once its bearer is hurt.</li>
 * </ul>
 *
 * <p>Authored as:
 * <pre>{@code
 * "equipment": [
 *   { "art": "cone", "piece": "pvzce:cone", "drop_particle": "pvzce:zombie_traffic_cone" },
 *   { "art": "zombie_flag", "health_below": 0.5, "drop_particle": "pvzce:zombie_flag" }
 * ]
 * }</pre>
 *
 * @param art          bone family prefix in the animation model: {@code cone} means the
 *                     bones {@code cone_1}, {@code cone_2}, ... The suffix is the damage
 *                     state, 1 being the intact drawing
 * @param piece        the armor piece that drives this equipment, or empty when the
 *                     zombie's own health does
 * @param healthBelow  for a health-driven piece: the health fraction under which the worn
 *                     drawing is shown
 * @param dropParticle what flies off when the piece is destroyed (or the zombie dies still
 *                     wearing it); empty means nothing does
 */
public record EquipmentDef(
        String art,
        Optional<Identifier> piece,
        float healthBelow,
        Optional<Identifier> dropParticle
) {
    /** The fraction a health-driven piece wears at when the entry does not say. */
    public static final float DEFAULT_HEALTH_BELOW = 0.5F;

    public static final Codec<EquipmentDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("art").forGetter(EquipmentDef::art),
            Identifier.CODEC.optionalFieldOf("piece").forGetter(EquipmentDef::piece),
            Codec.FLOAT.optionalFieldOf("health_below", DEFAULT_HEALTH_BELOW).forGetter(EquipmentDef::healthBelow),
            Identifier.CODEC.optionalFieldOf("drop_particle").forGetter(EquipmentDef::dropParticle)
    ).apply(i, EquipmentDef::new));

    public EquipmentDef {
        art = art == null ? "" : art.trim();
        piece = piece == null ? Optional.empty() : piece;
        dropParticle = dropParticle == null ? Optional.empty() : dropParticle;
        healthBelow = Float.isNaN(healthBelow) ? DEFAULT_HEALTH_BELOW : healthBelow;
    }

    /** True when an armor piece's durability drives this equipment rather than health. */
    public boolean armorDriven() {
        return piece.isPresent();
    }

    /** True when this entry can say anything at all about the model. */
    public boolean isUsable() {
        return !art.isEmpty();
    }
}
