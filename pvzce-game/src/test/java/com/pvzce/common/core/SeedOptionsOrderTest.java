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
        assertEquals(List.of("pea_shooter", "sunflower", "cherry_bomb", "wall_nut", "potato_mine"),
                plants.subList(0, 5), "the first five are the ones the original hands out first");
        assertTrue(plants.indexOf("melon_pult") < plants.indexOf("winter_melon"),
                "and the late ones keep their places too");
        assertTrue(plants.indexOf("sunflower") < plants.indexOf("puff_shroom"),
                "a plant met in world 1 stays ahead of one met in world 2");
    }

    @Test
    void aPlantWithNoAlmanacNumberSortsAfterTheRest() {
        PlantDef bowlingNut = BuiltInRegistries.PLANTS.get(Identifier.of("pvzce", "bowling_nut"));
        assertTrue(bowlingNut != null && bowlingNut.order() == PlantDef.DEFAULT_ORDER,
                "the bowling nut is a mini-game prop, not an almanac plant");
    }
}
