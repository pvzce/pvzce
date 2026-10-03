package com.pvzce.common.core;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.PlacementDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.tag.TagKey;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.tag.PvzceTags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The stacking rules of a cell, expressed only with tags and
 * {@link PlacementDef}.
 *
 * <h2>The two namespaces</h2>
 *
 * <p>Terrain tags say what a tile lets be planted <em>on it</em>; plant tags say
 * what a plant needs <em>beneath it</em>. They are separate registries, so
 * {@code #c:plantable} on a scene element and {@code #c:plantable} on a plant are
 * two unrelated tags that happen to share a word - which is what lets the same
 * convention spell "you may plant here" and "you may plant this".
 *
 * <h2>The matrix</h2>
 *
 * <pre>
 * terrain (#c:)        ground  plantable  water  unplantable
 *   grass                -        yes       -        -
 *   ground              yes        -        -        -
 *   roof_flat/slope     yes       -         -        -
 *   water                -         -       yes       -
 *   grave/crater         -         -        -       yes
 *
 * plant (#c:)          what it needs
 *   carrier            terrain carrying #c:ground or #c:water
 *   water_plant        terrain carrying #c:water
 *   requires_ground    terrain carrying #c:ground or #c:plantable
 *   plant_only         any plant strictly beneath it
 *   (none)             terrain or a plant beneath it carrying #c:plantable
 * </pre>
 *
 * <p>{@code #c:ground} and {@code #c:plantable} are not the same thing: bare
 * ground and roofs accept a carrier but not a plant directly; grass accepts a plant directly.
 *
 * <h2>Beneath, not "in the cell"</h2>
 *
 * <p>A candidate plant only ever considers supports <em>below its own layer</em>.
 * That is what makes placement order-independent: a peashooter and a potato mine
 * both live on layer 1, so neither can claim the other as a support, and a potato
 * mine can never end up in a flower pot regardless of what was planted first. A
 * lily pad (layer 0) is below the peashooter (layer 1), so it does support it.
 *
 * <p>Conflicts between plants on the same layer are {@link PlacementDef#group}'s
 * job - an empty group means "several of these may share a cell".
 *
 * <p>This class is deliberately free of {@code LevelServer}: the level supplies a
 * {@link Ctx} view and everything else is a pure function, so the whole matrix is
 * unit-testable without a running level.
 */
public final class PlantPlacement {
    /** Horizontal offset applied to a plant placed on a carrier, in world cells. */
    public static final float CARRIER_X_OFFSET = 0.06F;

    /** Visual top of a flower pot, in world cells. */
    public static final float FLOWER_POT_TOP = 0.38F;
    /** Visual top of a lily pad, in world cells. */
    public static final float LILY_PAD_TOP = 0.10F;

    private PlantPlacement() {
    }

    /** One plant already in the cell, with the entity id that breaks ties. */
    public record PlantLayer(PlantDef def, int entityId, boolean waterFilled) {
        public PlantLayer(PlantDef def, int entityId) { this(def, entityId, false); }
    }

    /** The terrain under a cell; {@code def == null} means "no level data". */
    public record Terrain(SceneElementDef def, float height) {
        public static final Terrain NONE = new Terrain(null, 0F);

        /** The terrain with no height; for tag-only queries. */
        public static Terrain of(SceneElementDef def) {
            return def == null ? NONE : new Terrain(def, 0F);
        }

        public boolean has(Identifier id) {
            return def != null && def.id() != null && def.id().equals(id);
        }
    }

    /**
     * Something a plant can be planted on: either the terrain or a plant beneath.
     *
     * <p>The two live in different registries, so "does this support carry
     * {@code #c:plantable}" has to ask the registry that owns the entry - a single
     * merged list of raw ids would silently read the plant tag for a terrain id.
     */
    public sealed interface Support permits TerrainSupport, PlantSupport {
        boolean has(TagKey<?> tag);
    }

    private record TerrainSupport(SceneElementDef def) implements Support {
        @Override
        public boolean has(TagKey<?> tag) {
            return def.id() != null && PvzceTags.SCENE_ELEMENTS.contains(tag.id(), def.id());
        }
    }

    private record PlantSupport(PlantDef def, boolean waterFilled) implements Support {
        @Override
        public boolean has(TagKey<?> tag) {
            return !(waterFilled && tag.equals(PvzceTags.PLANTABLE))
                    && def.id() != null && PvzceTags.PLANTS.contains(tag.id(), def.id());
        }
    }

    /**
     * Read-only view of one cell.
     *
     * <p>{@code plants} must be ordered bottom-to-top (see {@link #bottomFirst}).
     */
    public interface Ctx {
        Terrain terrain(int x, int y);

        List<PlantLayer> plants(int x, int y);
    }

    /** True when {@code def} carries {@code tag} in the plant registry. */
    public static boolean is(PlantDef def, TagKey<PlantDef> tag) {
        return def != null && def.id() != null && PvzceTags.PLANTS.contains(tag, def.id());
    }

    /** True when this plant carries other plants (flower pot, lily pad). */
    public static boolean isCarrier(PlantDef def) {
        return is(def, PvzceTags.CARRIER);
    }

    /**
     * Stack layer of a plant: where its {@link PlacementDef#layer()} says it goes,
     * except that a carrier always occupies {@link PlacementDef#LAYER_CARRIER} so
     * that content cannot accidentally place a pot above the plant it carries.
     */
    public static int layerIndex(PlantDef def) {
        if (def == null) {
            return PlacementDef.LAYER_GROUND;
        }
        if (isCarrier(def)) {
            return PlacementDef.LAYER_CARRIER;
        }
        return Math.max(PlacementDef.LAYER_CARRIER, def.placement().layer());
    }

    /** Orders a cell's plants bottom-to-top; ties fall back to the planting order. */
    public static Comparator<PlantLayer> bottomFirst() {
        return Comparator.comparingInt((PlantLayer layer) -> layerIndex(layer.def()))
                .thenComparingInt(PlantLayer::entityId);
    }

    /**
     * Whether {@code def} may be planted at a cell described by {@code ctx}.
     *
     * <p>The checks are ordered so the most specific tag wins: a lily pad carries
     * both {@code #c:water_plant} and {@code #c:carrier}, and the water rule is
     * what keeps it off the lawn.
     */
    public static boolean canPlace(PlantDef def, Ctx ctx, int x, int y) {
        if (def == null || ctx == null) {
            return false;
        }
        List<PlantLayer> plants = ctx.plants(x, y);
        // An upgrade is not a seed: the only legal cell is one that already holds the plant it
        // replaces, and nothing else about the cell is asked. The base passed these rules when it
        // was planted, and asking them again would refuse the case the rule exists for - a cattail
        // goes on a lily pad, and the lily pad is what makes that water cell plantable.
        if (def.upgrade().isPresent()) {
            return upgradeBasesInPlace(def.upgrade().get(), ctx, x, y, plants);
        }
        if (!distinctGroup(def, plants)) {
            return false;
        }
        Terrain terrain = ctx.terrain(x, y);
        List<Support> below = supportsOf(terrain, plants, layerIndex(def));

        if (is(def, PvzceTags.PLANT_ONLY)) {
            // Coffee bean: on a plant, and on a plant only. Any plant strictly
            // below counts - there is no separate "is a plant" tag to require,
            // because everything in the plant registry already is one.
            for (PlantLayer other : plants) {
                if (layerIndex(other.def()) < layerIndex(def)) {
                    return true;
                }
            }
            return false;
        }
        if (is(def, PvzceTags.WATER_PLANT)) {
            return terrainTagged(terrain, PvzceTags.SCENE_WATER)
                    || plants.stream().anyMatch(PlantLayer::waterFilled);
        }
        if (is(def, PvzceTags.GRAVE_ONLY)) {
            // Grave buster: the gravestone itself, and nowhere else. Checked before
            // `#c:requires_ground` because a grave is also unplantable, so the general rules
            // would refuse the one plant whose whole job is to stand on it.
            return terrainTagged(terrain, PvzceTags.SCENE_GRAVE);
        }
        if (is(def, PvzceTags.REQUIRES_GROUND)) {
            if (plants.stream().anyMatch(PlantLayer::waterFilled) && !isCarrier(def)) {
                return has(below, PvzceTags.PLANTABLE);
            }
            if (terrain.def() != null && terrain.def().surfaceClass().startsWith("ROOF") && !isCarrier(def)) {
                return has(below, PvzceTags.PLANTABLE);
            }
            // Flower pot, potato mine: pushed into the ground itself. Never water,
            // never a plant, never a carrier - a mine belongs in the dirt.
            return terrainTagged(terrain, PvzceTags.SCENE_GROUND)
                    || terrainTagged(terrain, PvzceTags.SCENE_PLANTABLE);
        }
        if (isCarrier(def)) {
            // Only the terrain can be under an empty carrier layer: ground holds the
            // pot, water holds the lily pad.
            return terrainTagged(terrain, PvzceTags.SCENE_GROUND)
                    || terrainTagged(terrain, PvzceTags.SCENE_WATER);
        }
        // Everything else: a plantable tile, or a plantable plant beneath it (which
        // is how a flower pot on bare ground - or on a roof - becomes plantable).
        return has(below, PvzceTags.PLANTABLE);
    }

    /**
     * Whether every base an upgrade wants is in place around {@code (x, y)}.
     *
     * <p>Two conditions beyond "the base is here":
     *
     * <ul>
     *   <li><b>A carrier base must be the topmost plant in its cell.</b> The original refuses to
     *       upgrade a lily pad that already has a plant on it, and the reason generalises: the
     *       upgrade takes the carrier's place, so a plant standing on the carrier would be left in
     *       the air. A base that is <em>not</em> a carrier is upgraded whatever is around it - a
     *       pumpkin shell over a repeater is exactly the case that must keep working.</li>
     *   <li><b>{@code adjacent} more bases must stand in the same row next to the cell.</b> The cob
     *       cannon is the one plant that asks for this (a 2x1 block of kernel-pults). Left first,
     *       then right, so the choice of <em>which</em> neighbour is consumed is deterministic
     *       rather than "whichever the list happened to hold first".</li>
     * </ul>
     */
    public static boolean upgradeBasesInPlace(PlantDef.Upgrade upgrade, Ctx ctx, int x, int y,
                                              List<PlantLayer> plants) {
        PlantLayer base = topmost(plants, upgrade.base());
        if (base == null) {
            return false;
        }
        if (isCarrier(base.def()) && plants.get(plants.size() - 1) != base) {
            return false;
        }
        if (upgrade.adjacent() == 0) {
            return true;
        }
        // Left before right, so which neighbour gets consumed is deterministic rather than
        // "whichever the list happened to hold first". A column off the board answers 0, so this
        // never has to know how wide the row is.
        return countBase(upgrade, ctx, x - 1, y) >= upgrade.adjacent()
                || countBase(upgrade, ctx, x + 1, y) >= upgrade.adjacent();
    }

    /** How many of the wanted base plants sit in the cell at {@code (x, y)}. */
    public static int countBase(PlantDef.Upgrade upgrade, Ctx ctx, int x, int y) {
        int found = 0;
        for (PlantLayer layer : ctx.plants(x, y)) {
            if (upgrade.base().equals(layer.def().id())) {
                found++;
            }
        }
        return found;
    }

    /** The topmost plant in the cell that is {@code id}, or {@code null}. */
    private static PlantLayer topmost(List<PlantLayer> plants, Identifier id) {
        PlantLayer found = null;
        for (PlantLayer layer : plants) {
            if (id.equals(layer.def().id())) {
                found = layer;
            }
        }
        return found;
    }

    /** True when the cell's terrain, not a plant in it, carries {@code tag}. */
    public static boolean terrainTagged(Terrain terrain, TagKey<SceneElementDef> tag) {
        return terrain != null && terrain.def() != null && terrain.def().id() != null
                && PvzceTags.SCENE_ELEMENTS.contains(tag, terrain.def().id());
    }

    private static boolean has(List<Support> supports, TagKey<?> tag) {
        for (Support support : supports) {
            if (support.has(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What a plant on {@code layer} may sit on: the terrain, plus the plants
     * strictly below that layer.
     *
     * <p>Including non-carrier plants is what lets a flower pot make a roof
     * plantable - the pot is a {@code #c:plantable} plant, and the plant above it
     * rests on the pot, not on the roof. Same-layer plants are excluded on purpose;
     * see the class comment.
     */
    public static List<Support> supportsOf(Terrain terrain, List<PlantLayer> plants, int layer) {
        List<Support> supports = new ArrayList<>(plants.size() + 1);
        if (terrain != null && terrain.def() != null
                && plants.stream().noneMatch(PlantLayer::waterFilled)) {
            supports.add(new TerrainSupport(terrain.def()));
        }
        for (PlantLayer other : plants) {
            if (other.def() != null && layerIndex(other.def()) < layer) {
                supports.add(new PlantSupport(other.def(), other.waterFilled()));
            }
        }
        return supports;
    }

    /** True when no plant of the same exclusion group already occupies the cell. */
    public static boolean distinctGroup(PlantDef def, List<PlantLayer> plants) {
        String group = def.placement().group();
        if (group == null || group.isEmpty()) {
            return true;
        }
        for (PlantLayer layer : plants) {
            if (group.equals(layer.def().placement().group())) {
                // A lily pad fits inside one empty filled pot. Other carriers and
                // occupied pots retain the normal mutual exclusion.
                if (isCarrier(def) && is(def, PvzceTags.WATER_PLANT)
                        && layer.waterFilled() && plants.size() == 1) continue;
                return false;
            }
        }
        return true;
    }

    /** Placement height of the next plant in a cell: terrain plus any carrier top. */
    public static float placementHeight(Ctx ctx, int x, int y) {
        Terrain terrain = ctx.terrain(x, y);
        float height = terrain == null ? 0F : terrain.height();
        for (PlantLayer layer : ctx.plants(x, y)) {
            if (isCarrier(layer.def())) {
                // A carrier already sits on whatever was beneath it, so its top is
                // its own base (the current height) plus its visual thickness.
                height += carrierTop(layer.def());
            }
        }
        return height;
    }

    /**
     * Visual top of a carrier relative to its own base, in world cells.
     *
     * <p>The two built-in carriers are named here because a {@code PlacementDef}
     * has no float field and adding one for a single visual constant would touch
     * every content file. An unknown carrier - a mod's - gets the smaller of the
     * two rather than being mistaken for whatever happened to be hard-coded; the
     * old code compared {@code id.path().equals("flower_pot")} and gave every other
     * carrier the lily pad's height.
     */
    public static float carrierTop(PlantDef carrier) {
        if (carrier == null || carrier.id() == null) {
            return 0F;
        }
        return switch (carrier.id().path()) {
            case "flower_pot" -> FLOWER_POT_TOP;
            default -> LILY_PAD_TOP;
        };
    }
}
