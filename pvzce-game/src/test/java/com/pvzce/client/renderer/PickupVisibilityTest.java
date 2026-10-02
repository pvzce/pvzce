package com.pvzce.client.renderer;

import com.pvzce.api.entity.EntityKind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule behind "the zombie side does not see the plant side's sun".
 *
 * <p>Tested here rather than through a screenshot because the whole of it is three strings, and the
 * case that matters (a player on the zombie side, a drop belonging to the plant side) needs a versus
 * level on the zombie side to see at all.
 */
public class PickupVisibilityTest {
    @Test
    void aDropBelongingToAnotherTeamIsNotDrawn() {
        assertFalse(PickupVisibility.isVisible("pvzce:zombie_team", EntityKind.RESOURCE,
                "pvzce:plant_team"));
        assertTrue(PickupVisibility.isVisible("pvzce:plant_team", EntityKind.RESOURCE,
                "pvzce:plant_team"));
    }

    @Test
    void nothingElseOnTheBoardIsHiddenByThisRule() {
        // Zombies, plants, projectiles: the rule is about pickups, and a zombie the player cannot
        // click is still a zombie the player has to see.
        assertTrue(PickupVisibility.isVisible("pvzce:zombie_team", "zombie", "pvzce:plant_team"));
        assertTrue(PickupVisibility.isVisible("pvzce:zombie_team", "plant", "pvzce:plant_team"));
    }

    @Test
    void aPlayerOnThePlantSideStillSeesEverySunOnItsLawn() {
        // The regression this could have been: hiding the *player's own* suns would break the whole
        // resource loop of every ordinary level.
        assertTrue(PickupVisibility.isVisible("pvzce:plant_team", EntityKind.RESOURCE,
                "pvzce:plant_team"));
        assertTrue(PickupVisibility.isVisible("pvzce:plant_team", EntityKind.RESOURCE, null));
    }

    @Test
    void nobodyInParticularHidesNothing() {
        // The editor and a spectator control no team, so there is no "mine" to compare against.
        assertTrue(PickupVisibility.isVisible(null, EntityKind.RESOURCE, "pvzce:plant_team"));
        assertTrue(PickupVisibility.isVisible("", EntityKind.RESOURCE, "pvzce:plant_team"));
    }
}
