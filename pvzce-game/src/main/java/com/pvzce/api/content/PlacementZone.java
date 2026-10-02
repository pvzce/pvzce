package com.pvzce.api.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.pvzce.api.content.mechanic.MechanicData;

/**
 * The part of the board a level reserves for one side of it.
 *
 * <p>Everything else about "can this go here" is terrain and stacking
 * ({@code PlantPlacement}, driven by {@code #c:} tags). This is the other half of
 * the question - <em>where on the lawn at all</em> - which the mini-games need and
 * which terrain cannot express: Wall-nut Bowling's red line is painted over ordinary
 * grass, so a tile-based answer would have to call that grass something else and
 * would then lose the lawn under the line.
 *
 * <p>Two mechanics decode this record, because "this part of the board is not yours" is one
 * idea with two subjects: {@code pvzce:placement_zone} restricts where a plant may go (through
 * {@code LevelMechanic.canPlacePlant}) and {@code pvzce:zombie_zone} restricts where a zombie
 * card may be spent (through {@code LevelMechanic.canPlaceZombie}). A versus level declares
 * both, which is how "plants on the left five columns, zombies on the right four" is written
 * down without either side's rule being hidden inside the other's.
 *
 * <p>Bounds are inclusive cell indices, and an omitted bound means "the board's
 * edge", so {@code {"min_x": 0, "max_x": 3}} is "the four leftmost columns" without
 * the level having to know how wide it is. The zone is enforced by
 * {@code LevelServer.canPlacePlant} / {@code LevelServer.canPlaceZombie} - the paths players,
 * the AI and the entity spawner all go through - and {@code LevelValidator} reports a zone that
 * cannot be satisfied.
 */
public record PlacementZone(int minX, int maxX, int minY, int maxY) implements MechanicData {
    /** No restriction: every cell of the board. */
    public static final PlacementZone FULL = new PlacementZone(0, Integer.MAX_VALUE, 0, Integer.MAX_VALUE);

    public static final MapCodec<PlacementZone> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.INT.optionalFieldOf("min_x", 0).forGetter(PlacementZone::minX),
            Codec.INT.optionalFieldOf("max_x", Integer.MAX_VALUE).forGetter(PlacementZone::maxX),
            Codec.INT.optionalFieldOf("min_y", 0).forGetter(PlacementZone::minY),
            Codec.INT.optionalFieldOf("max_y", Integer.MAX_VALUE).forGetter(PlacementZone::maxY)
    ).apply(i, PlacementZone::new));

    public static final Codec<PlacementZone> CODEC = MAP_CODEC.codec();

    public boolean contains(int x, int y) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }

    /** True when the zone covers the whole board, i.e. there is nothing to draw or check. */
    public boolean unrestricted(int width, int height) {
        return minX <= 0 && minY <= 0 && maxX >= width - 1 && maxY >= height - 1;
    }

    /** One message per impossible bound, for {@code LevelValidator}. */
    public java.util.List<String> validate(int width, int height) {
        return validate(width, height, "placement_zone");
    }

    /**
     * The same messages under a caller's own name.
     *
     * <p>Two mechanics decode this record - {@code pvzce:placement_zone} for the plantable area
     * and {@code pvzce:zombie_zone} for where a zombie card may be spent - and "one placement
     * area, one validator" is the whole reason they share a type. The label is the only thing
     * that has to differ, because a message that says {@code placement_zone} about a broken
     * zombie zone sends the author to the wrong block.
     */
    public java.util.List<String> validate(int width, int height, String label) {
        java.util.List<String> errors = new java.util.ArrayList<>();
        if (minX < 0 || minY < 0) {
            errors.add(label + " has a negative bound (min_x=" + minX + ", min_y=" + minY + ")");
        }
        if (maxX < minX || maxY < minY) {
            errors.add(label + " is empty (min_x=" + minX + ", max_x=" + maxX
                    + ", min_y=" + minY + ", max_y=" + maxY + ")");
        }
        if (restrictedOutside(width, height)) {
            errors.add(label + " lies outside the " + width + "x" + height + " board (min_x="
                    + minX + ", max_x=" + maxX + ", min_y=" + minY + ", max_y=" + maxY
                    + "): no cell could ever be used");
        }
        return errors;
    }

    /** True when the zone and the board do not overlap at all. */
    private boolean restrictedOutside(int width, int height) {
        return minX >= width || minY >= height || maxX < 0 || maxY < 0;
    }
}
