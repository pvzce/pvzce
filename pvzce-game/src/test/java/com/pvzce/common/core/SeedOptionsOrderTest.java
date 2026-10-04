package com.pvzce.common.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pvzce.api.content.PlantDef;
import com.pvzce.api.util.Identifier;
import com.pvzce.common.tag.TestContent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The bag and the seed chooser are read in the original's order, not the alphabet's.
 *
 * <p>Both pages list cards straight from {@link SeedOptions#allCards()}, so this is the one
 * place the order is decided: a player who owns four plants should see them in the order the
 * game handed them out - Peashooter, Sunflower, Cherry Bomb, Wall-nut - rather than the order
 * their ids happen to sort in, which put the Wall-nut first and the Peashooter third.
 */
class SeedOptionsOrderTest {
    @BeforeAll
    static void load() throws Exception {
        TestContent.loadBuiltInContentAndTags();
    }

    @Test
    void plantCardsComeOutInAlmanacOrder() {
        List<String> plants = new ArrayList<>();
        for (Identifier cardId : SeedOptions.allCards()) {
            SlotResolver.ResolvedCard card = SlotResolver.resolve(cardId).orElse(null);
            if (card != null && card.kind() == Slot.Kind.PLANT) {
                plants.add(card.content().path());
            }
        }
        assertTrue(plants.size() > 10, "the built-in set has a bag's worth of plants");
        List<String> original = List.of(
                "pea_shooter", "sunflower", "cherry_bomb", "wall_nut", "potato_mine", "snow_pea", "chomper", "repeater",
                "puff_shroom", "sun_shroom", "fume_shroom", "grave_buster", "hypno_shroom", "scaredy_shroom", "ice_shroom", "doom_shroom",
                "lily_pad", "squash", "threepeater", "tangle_kelp", "jalapeno", "spikeweed", "torchwood", "tall_nut",
                "sea_shroom", "plantern", "cactus", "blover", "split_pea", "starfruit", "pumpkin", "magnet_shroom",
                "cabbage_pult", "flower_pot", "kernel_pult", "coffee_bean", "garlic", "umbrella_leaf", "marigold", "melon_pult",
                "gatling_pea", "twin_sunflower", "gloom_shroom", "cattail", "winter_melon", "gold_magnet", "spikerock", "cob_cannon");
        assertEquals(original, plants.subList(0, original.size()), "the whole original roster keeps its seed order");
        assertTrue(plants.indexOf("melon_pult") < plants.indexOf("winter_melon"),
                "and the late ones keep their places too");
        assertTrue(plants.indexOf("sunflower") < plants.indexOf("puff_shroom"),
                "a plant met in world 1 stays ahead of one met in world 2");
    }

    @Test void aBorrowedEarlyCardKeepsItsPlaceInTheChooserPool() {
        var def = com.pvzce.testutil.TestLevels.copy(BuiltInRegistries.LEVELS.get(
                Identifier.withDefaultNamespace("yard/adventure/demo_level")))
                .slots(List.of(Identifier.withDefaultNamespace("pea_shooter"))).build();
        var pool = SeedOptions.cardPool(def, id -> id.equals(Identifier.withDefaultNamespace("cob_cannon")));
        assertEquals(List.of("pea_shooter", "cob_cannon"), pool.stream().map(Identifier::path).toList());
    }

    @Test
    void aPlantWithNoAlmanacNumberSortsAfterTheRest() {
        PlantDef bowlingNut = BuiltInRegistries.PLANTS.get(Identifier.of("pvzce", "bowling_nut"));
        assertTrue(bowlingNut != null && bowlingNut.order() == PlantDef.DEFAULT_ORDER,
                "the bowling nut is a mini-game prop, not an almanac plant");
    }
}
