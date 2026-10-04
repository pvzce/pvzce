package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Where a plant sits in its cell's stack and which plants it excludes.
 *
 * <h2>layer</h2>
 *
 * <p>A plant occupies layer slots starting at {@code layer}; a plant with
 * {@code layer = 2} sits above everything occupying two slots. The built-in
 * layers are:
 *
 * <ul>
 *   <li>{@code 1} - a plant on the ground (nearly everything);</li>
 *   <li>{@code 0} - a carrier: flower pot, lily pad. Carriers are also plants,
 *       but they are placed first and carry what follows;</li>
 *   <li>{@code 2} - a plant on a plant: coffee bean.</li>
 * </ul>
 *
 * <p>Two things follow from the layer alone: the sorting order within a cell
 * (lower first, ties by entity id, so the later-planted plant draws on top) and
 * what "the plant beneath me" means to the placement rules.
 *
 * <h2>count</h2>
 *
 * <p>How many layer slots the plant fills. A plant with {@code count = 2} at
 * layer 1 pushes anything planted above it to layer 3 or higher.
 *
 * <h2>width</h2>
 *
 * <p>Horizontal footprint in board cells; defaults to one. The entity is centred on the
 * footprint and every occupied cell refers to that same plant. This is independent of
 * {@code count}, which describes vertical stack slots.
 *
 * <h2>group</h2>
 *
 * <p>Optional mutual-exclusion group. Two plants with the same non-empty group
 * may not share a cell. {@code group} is empty for plants that may coexist with
 * their own kind (a potato mine under a peashooter is a different group from
 * both). Content that omits {@code group} gets {@link #PLANTABLE} - the
 * default {@code group = "plantable"} is what makes a second ordinary plant in
 * an occupied cell fail, which the old {@code feet} string happened to encode as
 * "same feet value" and expressed the rule by accident.
 *
 * <p>This record used to be a single free-form {@code feet} string that the
 * server switched on in five places and that also decided carrier detection; a
 * pack could not add a layer without a code change. It is now plain numbers plus
 * the {@code #c:carrier} / {@code #c:requires_ground} tags, so a mod adds a
 * plant-on-plant-cover (pumpkin) by tagging it rather than by asking for a
 * server branch.
 */
public record PlacementDef(int layer, int count, String group, int width) {
    public PlacementDef {
        width = Math.max(1, width);
    }

    public PlacementDef(int layer, int count, String group) {
        this(layer, count, group, 1);
    }
    /** Plants on the ground. */
    public static final int LAYER_CARRIER = 0;
    public static final int LAYER_GROUND = 1;
    public static final int LAYER_ON_PLANT = 2;

    /** The default exclusion group: one ordinary plant per cell. */
    public static final String GROUP_PLANTABLE = "plantable";
    /** Carriers may not stack on each other (no pot in a pot). */
    public static final String GROUP_CARRIER = "carrier";
    /** A ground plant that coexists with an ordinary plant (potato mine under a peashooter). */
    public static final String GROUP_UNDER_PLANT = "under_plant";

    /** A normal plant: on the ground, one per cell. */
    public static final PlacementDef PLANTABLE_DEF = new PlacementDef(LAYER_GROUND, 1, GROUP_PLANTABLE);
    /** A carrier plant: below normal plants, one per cell. */
    public static final PlacementDef CARRIER_DEF = new PlacementDef(LAYER_CARRIER, 1, GROUP_CARRIER);
    /** A plant that sits on another plant; several may share a cell. */
    public static final PlacementDef ON_PLANT_DEF = new PlacementDef(LAYER_ON_PLANT, 1, "");
    /** A plant that sits on the ground and coexists with a normal plant (potato mine). */
    public static final PlacementDef UNDER_PLANT_DEF = new PlacementDef(LAYER_GROUND, 1, GROUP_UNDER_PLANT);

    public static final Codec<PlacementDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("layer", LAYER_GROUND).forGetter(PlacementDef::layer),
            Codec.INT.optionalFieldOf("count", 1).forGetter(PlacementDef::count),
            Codec.STRING.optionalFieldOf("group", GROUP_PLANTABLE).forGetter(PlacementDef::group),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("width", 1).forGetter(PlacementDef::width)
    ).apply(i, PlacementDef::new));
}
