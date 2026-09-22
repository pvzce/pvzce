package com.pvzce.common.level.mechanic;

import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.server.level.LevelServer;

import java.util.ArrayList;
import java.util.List;

/**
 * "Stand a gravestone in a random free cell of a region", written once.
 *
 * <p>Two mechanics need it and they are the same act with different clocks: {@code
 * grave_field} lays the opening board out in one go, and {@code grave_spawner} puts a smashed
 * grave back while the level runs. What a grave <em>is</em> - the four designs, the region,
 * the refusal to overwrite a plant - is identical between them, and keeping one copy is what
 * stops "the graves that come back" from being a different kind of object than "the graves
 * that were there at the start".
 *
 * <p>The random source is the level's own, so a level's board is a function of its seed and
 * replays and resumed saves see the same lawn.
 */
final class GraveScatter {
    /**
     * The original's four tombstone designs, cycled so a field is never four of a kind.
     *
     * <p>Cycled rather than rolled: with four designs and thirteen graves, random choice would
     * routinely produce runs of one design, and the point of the mix is that the lawn reads as
     * a graveyard rather than as a repeated sprite.
     */
    static final List<Identifier> DESIGNS = List.of(
            PvzceIds.id("grave"),
            PvzceIds.id("grave_cross"),
            PvzceIds.id("grave_slab"),
            PvzceIds.id("grave_wide"));

    /**
     * How many cells one scatter pass may look at per grave it still needs.
     *
     * <p>A pass walks a shuffled list of the region's cells, so this is what stops "raise two
     * graves" from turning into a scan of the whole lawn on every tick while one cell is
     * blocked by a plant. Four rolls per grave finds an empty cell on any lawn that is not
     * effectively full, and a lawn that <em>is</em> full is recorded as full by the caller's
     * own count rather than by a failed search.
     */
    private static final int ATTEMPTS_PER_GRAVE = 4;

    /**
     * What a scatter did.
     *
     * @param raised     how many graves were actually stood up
     * @param nextDesign the design cursor after the last one, for the caller to keep
     */
    record Placement(int raised, int nextDesign) {
    }

    /** The designs a caller asked for, or the built-in four when it named none. */
    static List<Identifier> designsOrBuiltIn(List<Identifier> designs) {
        return designs == null || designs.isEmpty() ? DESIGNS : designs;
    }

    /**
     * Stands up to {@code wanted} graves in columns {@code minX..maxX}.
     *
     * <p>The region's cells are shuffled from the level's own random source and tried in that
     * order, rather than rolling a cell per attempt and giving up on a collision: thirteen
     * graves in a five-by-five half-lawn is the original's 2-10, and a "roll and retry" loop
     * that quits on the first already-grave cell leaves a lawn with eight of them. Sampling
     * without replacement is the same amount of randomness and always fills the region when
     * there is room for it.
     *
     * <p>A cell {@link LevelServer#placeGrave} refuses - a plant is standing on it, or it is
     * not painted at all - is simply skipped; the caller asked for "up to" this many.
     */
    static Placement place(LevelServer level, int minX, int maxX, List<Identifier> designs,
                           int wanted, int nextDesign) {
        List<Identifier> pool = designsOrBuiltIn(designs);
        int columns = maxX - minX + 1;
        int rows = level.height();
        if (pool.isEmpty() || columns <= 0 || rows <= 0 || wanted <= 0) {
            return new Placement(0, nextDesign);
        }
        List<int[]> cells = new ArrayList<>(columns * rows);
        for (int x = minX; x <= maxX; x++) {
            for (int y = 0; y < rows; y++) {
                cells.add(new int[]{x, y});
            }
        }
        java.util.Collections.shuffle(cells, level.random());
        int raised = 0;
        int cursor = nextDesign;
        int attempts = 0;
        int budget = wanted * ATTEMPTS_PER_GRAVE;
        for (int[] cell : cells) {
            if (raised >= wanted || attempts >= budget) {
                break;
            }
            attempts++;
            Identifier element = pool.get(Math.floorMod(cursor, pool.size()));
            if (level.placeGrave(element, cell[0], cell[1])) {
                cursor++;
                raised++;
            }
        }
        return new Placement(raised, cursor);
    }

    /** True when the element exists and is a gravestone, for a mechanic's validation. */
    static boolean isGraveElement(Identifier id) {
        SceneElementDef element = BuiltInRegistries.SCENE_ELEMENTS.get(id);
        return element != null && PvzceIds.SURFACE_GRAVE.equals(element.surfaceClass());
    }

    private GraveScatter() {
    }
}
