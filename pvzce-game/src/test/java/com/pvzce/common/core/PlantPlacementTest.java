package com.pvzce.common.core;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.content.SceneElementDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.tag.PvzceTags;
import com.pvzce.common.tag.TestContent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planting matrix, end to end: built-in tag files, the two tag namespaces and
 * the rules in {@link PlantPlacement}.
 *
 * <p>Everything here is the contract a player sees - "a lily pad goes on water",
 * "a potato mine does not go in a flower pot" - so a change to the tag files that
 * silently reshuffles placement fails here rather than in play.
 */
class PlantPlacementTest {
    private static final String[] SURFACES = {"grass", "ground", "water", "roof_flat", "roof_slope", "grave", "crater"};

    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    private static void assertTag(String surface, String tag, boolean expected,
                                  com.pvzce.api.tag.TagKey<SceneElementDef> key, Identifier id) {
        boolean actual = PvzceTags.SCENE_ELEMENTS.contains(key, id);
        if (expected) {
            assertTrue(actual, surface + " must carry #c:" + tag);
        } else {
            assertFalse(actual, surface + " must not carry #c:" + tag);
        }
    }

    private static PlantDef plant(String path) {
        PlantDef def = BuiltInRegistries.PLANTS.get(Identifier.withDefaultNamespace(path));
        assertNotNull(def, "missing plant " + path);
        return def;
    }

    private static SceneElementDef element(String path) {
        SceneElementDef def = BuiltInRegistries.SCENE_ELEMENTS.get(Identifier.withDefaultNamespace(path));
        assertNotNull(def, "missing scene element " + path);
        return def;
    }

    /** A cell of the given terrain with the given plants already in it. */
    private record CellContext(String terrain, List<PlantPlacement.PlantLayer> layers) implements PlantPlacement.Ctx {
        CellContext(String terrain, String... plantsInCell) {
            this(terrain, layersOf(plantsInCell));
        }

        private static List<PlantPlacement.PlantLayer> layersOf(String[] paths) {
            List<PlantPlacement.PlantLayer> layers = new ArrayList<>();
            int id = 1;
            for (String path : paths) {
                layers.add(new PlantPlacement.PlantLayer(plant(path), id++));
            }
            return layers;
        }

        @Override
        public PlantPlacement.Terrain terrain(int x, int y) {
            SceneElementDef def = element(terrain);
            return new PlantPlacement.Terrain(def, def.heightAt(x + 0.5F, 9));
        }

        @Override
        public List<PlantPlacement.PlantLayer> plants(int x, int y) {
            // Same order the level hands over, so a test stack and a real one agree.
            return layers.stream().sorted(PlantPlacement.bottomFirst()).toList();
        }
    }

    private static boolean canPlace(String plantPath, String terrain, String... existing) {
        return PlantPlacement.canPlace(plant(plantPath), new CellContext(terrain, existing), 0, 0);
    }

    /** Every plant that must be rejected on water. */
    private static final String[] NOT_ON_WATER =
            {"pea_shooter", "sunflower", "flower_pot", "potato_mine"};

    // ------------------------------------------------------------------
    // Terrain
    // ------------------------------------------------------------------

    @Test
    void terrainTagsMatchTheMatrix() {
        record Expect(String surface, boolean ground, boolean plantable, boolean water, boolean unplantable) {
        }
        List<Expect> expected = List.of(
                new Expect("grass", true, true, false, false),
                new Expect("ground", true, false, false, false),
                new Expect("roof_flat", true, true, false, false),
                new Expect("roof_slope", true, true, false, false),
                new Expect("water", false, false, true, false),
                new Expect("grave", false, false, false, true),
                new Expect("crater", false, false, false, true));
        assertEquals(SURFACES.length, expected.size(), "every built-in tile must be covered");
        for (Expect e : expected) {
            Identifier id = Identifier.withDefaultNamespace(e.surface());
            assertTag(e.surface(), "ground", e.ground(), PvzceTags.SCENE_GROUND, id);
            assertTag(e.surface(), "plantable", e.plantable(), PvzceTags.SCENE_PLANTABLE, id);
            assertTag(e.surface(), "water", e.water(), PvzceTags.SCENE_WATER, id);
            assertTag(e.surface(), "unplantable", e.unplantable(), PvzceTags.SCENE_UNPLANTABLE, id);
        }
        assertTrue(PvzceTags.missingPlacementTags().isEmpty(),
                "every placement tag must ship with the built-in pack: " + PvzceTags.missingPlacementTags());
    }

    @Test
    void grassAcceptsAPlantDirectly() {
        assertTrue(canPlace("pea_shooter", "grass"));
        assertTrue(canPlace("sunflower", "grass"));
        assertTrue(canPlace("wall_nut", "grass"));
    }

    @Test
    void roofsAcceptPlantsDirectlyAndAlsoCarryPots() {
        for (String roof : new String[]{"roof_flat", "roof_slope"}) {
            assertTrue(canPlace("pea_shooter", roof), roof + " is plantable ground");
            assertTrue(canPlace("flower_pot", roof), roof + " accepts a flower pot");
            assertTrue(canPlace("pea_shooter", roof, "flower_pot"), "and a pot on it also carries a plant");
        }
    }

    @Test
    void bareGroundNeedsACarrier() {
        assertTrue(canPlace("flower_pot", "ground"));
        assertFalse(canPlace("pea_shooter", "ground"), "bare ground must not accept a plant directly");
        assertTrue(canPlace("pea_shooter", "ground", "flower_pot"));
        assertFalse(canPlace("lily_pad", "ground"), "the lily pad is water-only");
    }

    @Test
    void waterOnlyAcceptsTheLilyPad() {
        assertTrue(canPlace("lily_pad", "water"));
        assertFalse(canPlace("pea_shooter", "water"));
        assertFalse(canPlace("flower_pot", "water"), "a pot cannot stand on open water");
        assertFalse(canPlace("flower_pot", "water", "lily_pad"),
                "nor on a lily pad: #c:requires_ground means the terrain itself is ground");
        assertTrue(canPlace("pea_shooter", "water", "lily_pad"), "the lily pad makes water plantable");
        assertFalse(canPlace("potato_mine", "water", "lily_pad"), "a mine still needs real ground");
    }

    @Test
    void graveAndCraterAcceptNothing() {
        for (String blocked : new String[]{"grave", "crater"}) {
            for (String path : new String[]{"pea_shooter", "sunflower", "flower_pot", "lily_pad", "potato_mine"}) {
                assertFalse(canPlace(path, blocked), path + " must not be plantable on " + blocked);
            }
        }
    }

    // ------------------------------------------------------------------
    // Carriers
    // ------------------------------------------------------------------

    @Test
    void potatoMineNeedsRealGroundAndNeverACarrier() {
        assertTrue(canPlace("potato_mine", "grass"), "the lawn is real ground");
        assertTrue(canPlace("potato_mine", "roof_flat", "flower_pot"), "a pot on a roof counts as ground");
        assertFalse(canPlace("potato_mine", "water", "lily_pad"), "a lily pad is not ground");
        assertTrue(canPlace("potato_mine", "ground"), "bare ground is ground, so a mine belongs in it");
    }

    @Test
    void coffeeBeanOnlyGoesOnOtherPlants() {
        assertTrue(canPlace("coffee_bean", "grass", "pea_shooter"));
        assertTrue(canPlace("coffee_bean", "water", "lily_pad"), "a carrier is a plant too");
        assertFalse(canPlace("coffee_bean", "grass"), "coffee bean needs a plant under it");
    }

    // ------------------------------------------------------------------
    // Group exclusion and layering
    // ------------------------------------------------------------------

    @Test
    void oneOrdinaryPlantPerCell() {
        assertFalse(canPlace("pea_shooter", "grass", "sunflower"));
        assertFalse(canPlace("flower_pot", "grass", "flower_pot"), "no pot in a pot");
    }

    @Test
    void layeredPlantsCoexist() {
        assertTrue(canPlace("pea_shooter", "grass", "potato_mine"),
                "a mine and a plant share a cell");
        assertTrue(canPlace("potato_mine", "grass", "pea_shooter"),
                "and in either planting order");
        assertTrue(canPlace("pea_shooter", "grass", "flower_pot"));
    }

    @Test
    void carriersSitBelowPlantsAndPlantsOnPlantsSitAbove() {
        assertTrue(PlantPlacement.isCarrier(plant("flower_pot")));
        assertTrue(PlantPlacement.isCarrier(plant("lily_pad")));
        assertFalse(PlantPlacement.isCarrier(plant("pea_shooter")));

        assertTrue(PlantPlacement.layerIndex(plant("flower_pot"))
                < PlantPlacement.layerIndex(plant("pea_shooter")),
                "a carrier must draw below the plant it carries");
        assertTrue(PlantPlacement.layerIndex(plant("coffee_bean"))
                > PlantPlacement.layerIndex(plant("pea_shooter")),
                "a plant on a plant must draw above it");
    }

    @Test
    void placementHeightStacksCarrierTops() {
        assertEquals(0F, PlantPlacement.placementHeight(new CellContext("grass"), 0, 0), 0.0001F);
        assertEquals(PlantPlacement.FLOWER_POT_TOP,
                PlantPlacement.placementHeight(new CellContext("grass", "flower_pot"), 0, 0), 0.0001F);
        assertEquals(PlantPlacement.LILY_PAD_TOP,
                PlantPlacement.placementHeight(new CellContext("water", "lily_pad"), 0, 0), 0.0001F);
        // A sloped roof still contributes its own height when a pot stands on it.
        PlantPlacement.Ctx slope = new CellContext("roof_slope", "flower_pot");
        assertTrue(PlantPlacement.placementHeight(slope, 4, 0) > PlantPlacement.FLOWER_POT_TOP);
    }

}
