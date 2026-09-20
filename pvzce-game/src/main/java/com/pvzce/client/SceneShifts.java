package com.pvzce.client;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.capability.PlantCapability;
import com.pvzce.api.content.capability.TypedCapability;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.PvzceIds;
import com.pvzce.common.capability.plant.GraveBusterCapability;
import com.pvzce.common.core.BuiltInRegistries;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.common.util.MathUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Scene cells whose element is not drawn in place right now.
 *
 * <p>Two things push an element off its cell, and both are the same picture: the element is
 * drawn <em>lower</em> than it belongs and everything below the cell's ground line is cut off,
 * so what shows is the top slice of its art standing on the bottom edge of the cell.
 *
 * <ul>
 *   <li>a tombstone the {@code grave_spawner} raises mid-level <em>comes up</em>: it starts a
 *       whole cell below and climbs into place over {@link #RISE_SECONDS};</li>
 *   <li>a tombstone a grave buster is eating <em>goes down</em>: it sinks by however far the
 *       meal has got, which is the plant's own height (see
 *       {@link GraveBusterCapability#SINK_DEPTH_CELLS}).</li>
 * </ul>
 *
 * <p>Which elements may rise is a tag ({@code #pvzce:rises_from_ground}) rather than a surface
 * class, for the same reason placement is: a pack that ships its own headstone says so in a tag
 * file and gets the animation, and nothing here has to learn its id. The sink needs no rule -
 * only a grave buster sinks, and only because it is one.
 *
 * <p>Only a cell that <em>changes during play</em> is ever recorded as rising. A level's opening
 * graves were always there - painting them is not an event - and a full resync is the same board
 * the client already had. The distinction is made by the caller, which is the only place that
 * can tell the two apart (see {@link ClientLevel#applyScene}).
 *
 * <p>The rises are clocked on the level's game time rather than on the wall clock, so a paused
 * game freezes a tombstone halfway out of the lawn along with everything else. Everything else
 * is read straight off the mirror once per frame ({@link #sync}), which is also why a grave
 * buster eaten mid-meal needs no bookkeeping: the moment it is gone, so is its bite.
 */
public final class SceneShifts {
    /** How long an element takes to come all the way up, in game seconds. */
    public static final float RISE_SECONDS = 0.35F;

    /**
     * One cell's element, out of place.
     *
     * @param under the element to draw where it used to be - the lawn it is pushing through or
     *              sinking into
     * @param sink  how far below its cell the element is drawn, 0 (in place) to 1 (gone)
     */
    public record Shift(String under, float sink) {
    }

    /** One cell's rise, as it was recorded. */
    private record Entry(String under, double startSeconds) {
    }

    private final Map<Long, Entry> rising = new HashMap<>();
    /** This frame's answer, rebuilt by {@link #sync}. */
    private final Map<Long, Shift> current = new HashMap<>();

    /**
     * Records that a cell changed to {@code now} during play.
     *
     * <p>Anything that is not tagged as rising is forgotten rather than left alone: a cell can
     * change twice in a second, and a stale entry would draw yesterday's lawn over today's
     * crater.
     *
     * @param under the element the cell held before, or {@code null} when it held nothing
     */
    public void cellChanged(int x, int y, String under, String now, double nowSeconds) {
        long key = key(x, y);
        rising.remove(key);
        if (now == null || !rises(now)) {
            return;
        }
        rising.put(key, new Entry(under == null ? PvzceIds.GRASS.toString() : under, nowSeconds));
    }

    /**
     * Recomputes what is out of place on the board, once per frame.
     *
     * <p>A rise that has finished, and a plant that has stopped sinking, are simply not in the
     * new answer - which is how a half-eaten grave goes back to being a whole one the moment
     * the grave buster chewing it is eaten.
     */
    public void sync(double nowSeconds, Iterable<ClientEntity> entities) {
        current.clear();
        rising.entrySet().removeIf(entry -> progress(entry.getValue(), nowSeconds) >= 1F);
        for (Map.Entry<Long, Entry> entry : rising.entrySet()) {
            float progress = progress(entry.getValue(), nowSeconds);
            current.put(entry.getKey(), new Shift(entry.getValue().under(), 1F - progress));
        }
        for (ClientEntity entity : entities) {
            Float sunk = graveBite(entity);
            if (sunk != null) {
                current.put(key(entity.gridX(), entity.gridY()),
                        new Shift(PvzceIds.GRASS.toString(), sunk));
            }
        }
    }

    /** The shift at a cell, or {@code null} when the cell's element is drawn in place. */
    public Shift at(int x, int y) {
        return current.get(key(x, y));
    }

    /** Forgets every rise; a level instance's board is not the next one's. */
    public void clear() {
        rising.clear();
        current.clear();
    }

    /** How far through its rise an entry is, 0 (buried) to 1 (in place). */
    private static float progress(Entry entry, double nowSeconds) {
        return MathUtil.clamp01((float) ((nowSeconds - entry.startSeconds()) / RISE_SECONDS));
    }

    /**
     * How far a plant has sunk into the grave it is eating, or {@code null} when it is not one.
     *
     * <p>The plant's own height is the answer, in the shared {@code SINK_DEPTH_CELLS} units: the
     * simulation already publishes it every sync, and a second progress field would only be a
     * second answer to the same question.
     */
    private static Float graveBite(ClientEntity entity) {
        if (entity.height() >= 0F || !eatsGraves(entity.defId())) {
            return null;
        }
        return MathUtil.clamp01(-entity.height() / GraveBusterCapability.SINK_DEPTH_CELLS);
    }

    /** True when this content is a plant that clears gravestones. */
    private static boolean eatsGraves(Identifier defId) {
        PlantDef def = defId == null ? null : BuiltInRegistries.PLANTS.get(defId);
        if (def == null) {
            return false;
        }
        for (TypedCapability<PlantCapability> capability : def.resolvedCapabilities()) {
            if (capability.value() instanceof GraveBusterCapability) {
                return true;
            }
        }
        return false;
    }

    /** True when this element is one that comes up out of the ground. */
    private static boolean rises(String elementId) {
        Identifier id = Identifier.tryParse(elementId);
        return id != null && PvzceTags.contains(PvzceTags.SCENE_RISES_FROM_GROUND, id);
    }

    /**
     * The map key for a cell.
     *
     * <p>Two ints packed into one long: a record key would allocate on every lookup, and this is
     * asked once per cell per frame by the scene renderer.
     */
    private static long key(int x, int y) {
        return ((long) y << 32) | (x & 0xFFFFFFFFL);
    }
}
